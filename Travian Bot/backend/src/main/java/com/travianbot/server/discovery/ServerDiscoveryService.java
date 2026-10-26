package com.travianbot.server.discovery;

import com.travianbot.botlog.service.BotEventService;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.server.service.ServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Probes a generated list of candidate Travian URLs to find live game worlds.
 * <p>
 * Each candidate is asked for {@code /map.sql} (the same public SQL dump the
 * intel module consumes). Any URL that returns HTTP 200 with a body containing
 * {@code INSERT INTO} is considered a live Travian server. The probe is
 * deliberately lightweight — we read only the first 64 KB of the body before
 * deciding, so even multi-MB dumps don't tie up a worker.
 * <p>
 * Design choices:
 * <ul>
 *   <li><b>Fixed 16-thread pool</b> — high enough that ~600 probes finish in
 *       ~30-60 s, low enough that we don't slam Travian's CDN. A 50 ms jitter
 *       between submissions further spreads the load.</li>
 *   <li><b>Defensive probe</b> — any single probe that throws is logged at
 *       DEBUG and counted as DEAD. Discovery never aborts mid-run.</li>
 *   <li><b>Inactive by default</b> — newly-discovered servers are saved with
 *       {@code active = false}. The user explicitly enables the worlds they
 *       want to track so the daily map.sync scheduler doesn't suddenly grow a
 *       dozen extra targets.</li>
 *   <li><b>No per-URL events</b> — emitting one event per probe would flood the
 *       log. We log a single summary line at INFO when the run finishes.</li>
 * </ul>
 * <p>
 * Headers mirror {@link com.travianbot.intel.service.MapSqlFetcher} (Chrome
 * UA, gzip-aware) so any server that accepts the intel scrape also accepts the
 * probe. Timeouts are SHORTER though — 10 s connect + 15 s read — because we'd
 * rather give up on a slow box and move on than block a worker for a minute.
 */
@Service
public class ServerDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ServerDiscoveryService.class);

    private static final int THREAD_POOL_SIZE = 16;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long SUBMIT_JITTER_MS = 50L;
    /** Cap body read at 64 KB — we only need the first INSERT line to decide. */
    private static final int MAX_BODY_PEEK_BYTES = 64 * 1024;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final ServerRepository serverRepository;
    private final ServerService serverService;
    private final CandidateUrlGenerator candidateUrlGenerator;
    /**
     * Held but currently unused — {@link BotEventService#record} is
     * account-scoped (requires an {@code accountId}), and discovery is a
     * global action with no account context. The summary line at INFO level
     * fulfils the "one event per run" intent for now; if a future contributor
     * adds a global event channel, the dependency is already wired.
     */
    @SuppressWarnings("unused")
    private final BotEventService botEventService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ServerDiscoveryService(ServerRepository serverRepository,
                                  ServerService serverService,
                                  CandidateUrlGenerator candidateUrlGenerator,
                                  BotEventService botEventService) {
        this.serverRepository = serverRepository;
        this.serverService = serverService;
        this.candidateUrlGenerator = candidateUrlGenerator;
        this.botEventService = botEventService;
    }

    /**
     * Probe every candidate URL and persist newly-found live servers as
     * {@code active = false} {@link Server} rows. Existing rows are left
     * untouched — discovery never flips a user's enable/disable choice.
     *
     * @return summary including counts and the canonical URLs of every live
     *         server found this run (both newly created AND already-known)
     */
    public DiscoveryReport discoverAll() {
        long startNs = System.nanoTime();
        List<String> candidates = candidateUrlGenerator.generateAll();
        log.info("ServerDiscovery: probing {} candidate URLs", candidates.size());

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<CompletableFuture<ProbeOutcome>> futures = new ArrayList<>(candidates.size());

        try {
            for (String url : candidates) {
                final String target = url;
                futures.add(CompletableFuture.supplyAsync(() -> probe(target), pool));
                // Politeness: tiny jitter so we don't fire 600 simultaneous DNS
                // lookups in the first 200 ms. Synchronous sleep on the calling
                // thread is fine here — submission isn't the bottleneck.
                sleepQuietly(SUBMIT_JITTER_MS);
            }

            List<String> liveUrls = new ArrayList<>();
            for (CompletableFuture<ProbeOutcome> f : futures) {
                try {
                    ProbeOutcome outcome = f.get();
                    if (outcome.live()) {
                        liveUrls.add(outcome.canonicalUrl());
                    }
                } catch (Exception e) {
                    // Already swallowed inside probe(); reaching here means the
                    // CompletableFuture itself blew up, not the HTTP call.
                    log.debug("Probe future failed: {}", e.getMessage());
                }
            }

            return persistAndSummarise(candidates.size(), liveUrls, startNs);
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }

    /**
     * Persist newly-discovered URLs and assemble the report. Pulled into its
     * own method so the executor lifecycle in {@link #discoverAll()} is easy
     * to read.
     */
    private DiscoveryReport persistAndSummarise(int probed, List<String> liveUrls, long startNs) {
        int discovered = 0;
        int alreadyKnown = 0;
        for (String url : liveUrls) {
            String canonical = Server.canonicalUrl(url);
            if (serverRepository.findByUrl(canonical).isPresent()) {
                alreadyKnown++;
                continue;
            }
            try {
                String fallbackName = deriveFallbackName(canonical);
                // Go through the service so callers downstream of getOrCreate
                // (intel scheduler, etc.) see the same row shape they always
                // have. The service-created row defaults to active=true, so
                // we flip it via a second save — discovered worlds stay
                // inactive until the user explicitly enables them.
                Server fresh = serverService.getOrCreate(canonical, fallbackName);
                if (!Boolean.FALSE.equals(fresh.getActive())) {
                    fresh.setActive(false);
                    serverRepository.save(fresh);
                }
                discovered++;
                log.debug("Discovery: new server saved url='{}' name='{}' (inactive by default)",
                        canonical, fallbackName);
            } catch (RuntimeException e) {
                log.warn("Discovery: failed to persist {}: {}", canonical, e.getMessage());
            }
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        List<String> sortedLive = new ArrayList<>(liveUrls);
        Collections.sort(sortedLive);
        log.info("ServerDiscovery: probed={} live={} new={} alreadyKnown={} durationMs={}",
                probed, liveUrls.size(), discovered, alreadyKnown, durationMs);

        return new DiscoveryReport(probed, discovered, alreadyKnown, durationMs, sortedLive);
    }

    /**
     * Single-URL probe. Returns a {@link ProbeOutcome} — never throws. Any
     * transport error / timeout / parse failure is logged at DEBUG and the
     * URL is treated as dead.
     */
    private ProbeOutcome probe(String baseUrl) {
        String canonical = Server.canonicalUrl(baseUrl);
        String target = canonical + "/map.sql";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/plain, application/sql, */*")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                log.debug("Probe DEAD ({}): {}", status, target);
                return new ProbeOutcome(canonical, false);
            }
            byte[] raw = response.body() == null ? new byte[0] : response.body();
            String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
            byte[] decoded = maybeDecompress(raw, encoding);
            int peekLen = Math.min(decoded.length, MAX_BODY_PEEK_BYTES);
            String peek = new String(decoded, 0, peekLen, StandardCharsets.UTF_8);

            // Case-insensitive substring search — Travian's dumps consistently
            // contain `INSERT INTO` but we tolerate `insert into` defensively.
            if (peek.toLowerCase().contains("insert into")) {
                log.debug("Probe LIVE: {}", canonical);
                return new ProbeOutcome(canonical, true);
            }
            log.debug("Probe DEAD (200 but no INSERT INTO): {}", target);
            return new ProbeOutcome(canonical, false);
        } catch (java.net.http.HttpTimeoutException e) {
            log.debug("Probe TIMEOUT: {}", target);
            return new ProbeOutcome(canonical, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeOutcome(canonical, false);
        } catch (Exception e) {
            log.debug("Probe ERROR {}: {}", target, e.getClass().getSimpleName());
            return new ProbeOutcome(canonical, false);
        }
    }

    /**
     * Same gzip handling as {@link com.travianbot.intel.service.MapSqlFetcher}:
     * checks both the {@code Content-Encoding} header AND the gzip magic bytes
     * so we don't miss servers that send the stream without setting the
     * header. Decode failure returns the raw bytes — the {@code INSERT INTO}
     * check below will simply fail and we'll log the URL as dead.
     */
    private byte[] maybeDecompress(byte[] raw, String contentEncoding) {
        boolean headerSaysGzip = contentEncoding.contains("gzip");
        boolean magicSaysGzip = raw.length >= 2
                && (raw[0] & 0xFF) == 0x1f
                && (raw[1] & 0xFF) == 0x8b;
        if (!headerSaysGzip && !magicSaysGzip) return raw;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8 * 1024, raw.length * 4))) {
            byte[] buf = new byte[8 * 1024];
            int written = 0;
            int n;
            while ((n = gz.read(buf)) != -1 && written < MAX_BODY_PEEK_BYTES) {
                int take = Math.min(n, MAX_BODY_PEEK_BYTES - written);
                out.write(buf, 0, take);
                written += take;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * Derive a human-readable name from a canonical URL host. Example:
     * {@code https://ts5.x1.czsk.travian.com} → {@code "czsk x1 — ts5"}.
     * Falls back to the raw host if the regex doesn't match (defensive — we
     * never want a NPE in the persistence step to abort the whole discovery).
     */
    static String deriveFallbackName(String canonicalUrl) {
        if (canonicalUrl == null) return "unknown";
        String host = canonicalUrl;
        int schemeIdx = host.indexOf("://");
        if (schemeIdx >= 0) host = host.substring(schemeIdx + 3);
        int slashIdx = host.indexOf('/');
        if (slashIdx >= 0) host = host.substring(0, slashIdx);

        // Match shapes:
        //   ts5.x1.czsk.travian.com -> "czsk x1 — ts5"
        //   ts5.czsk.travian.com    -> "czsk — ts5"
        //   tx3.com.travian.com     -> "com — tx3"
        String[] parts = host.split("\\.");
        // Expect …<world>.<community>.travian.com (3+ parts before "travian").
        if (parts.length >= 4 && "travian".equals(parts[parts.length - 2])
                && "com".equals(parts[parts.length - 1])) {
            String community = parts[parts.length - 3];
            if (parts.length == 4) {
                // tx3.com.travian.com  -> world=tx3, community=com
                return community + " — " + parts[0];
            }
            if (parts.length == 5) {
                // ts5.x1.czsk.travian.com -> ts=parts[0], speed=parts[1]
                return community + " " + parts[1] + " — " + parts[0];
            }
        }
        return host;
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Internal probe result — never leaves this class. */
    private record ProbeOutcome(String canonicalUrl, boolean live) {}
}
