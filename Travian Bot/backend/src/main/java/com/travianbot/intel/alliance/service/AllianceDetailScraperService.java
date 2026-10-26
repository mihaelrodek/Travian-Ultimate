package com.travianbot.intel.alliance.service;

import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.domain.MapAllianceMember;
import com.travianbot.intel.alliance.persistence.MapAllianceMemberRepository;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.intel.alliance.scraper.AllianceDetailParser;
import com.travianbot.intel.alliance.scraper.AllianceDetailParser.ParsedAllianceDetail;
import com.travianbot.intel.alliance.scraper.AllianceDetailParser.ParsedAllianceMember;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * On-demand scraper for the in-game alliance detail page.
 * <p>
 * Triggered by the {@code POST /api/servers/{id}/intel/alliances/{aid}/refresh-detail}
 * endpoint. Anonymous HTTP fetch — alliance pages are public on most servers;
 * a few skins gate behind login, in which case the scraper logs a warning
 * and returns the existing cached row unchanged. See the TODO inside
 * {@link #fetch(String)} for the planned cookie-fallback path.
 * <p>
 * <strong>24-hour cache.</strong> A successful scrape stamps
 * {@link MapAlliance#getLastDetailScrapedAt()}; subsequent calls within 24h
 * return the cached row without hitting the network. Pass {@code force=true}
 * to bypass the cache (used by the UI's "refresh" button).
 * <p>
 * <strong>Why fetch HTML inline instead of reusing {@code MapSqlFetcher}?</strong>
 * That fetcher embeds a SQL-dump sanity check that would always fail for an
 * HTML response. The two services share the same Chrome UA and gzip handling
 * pattern (copied verbatim) but their happy-path expectations differ enough
 * that a shared base class would be more friction than gain.
 */
@Service
@Transactional
public class AllianceDetailScraperService {

    private static final Logger log = LoggerFactory.getLogger(AllianceDetailScraperService.class);

    /** Cache TTL — repeated scrapes within this window are no-ops unless {@code force}. */
    static final Duration CACHE_TTL = Duration.ofHours(24);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final MapAllianceRepository allianceRepository;
    private final MapAllianceMemberRepository memberRepository;
    private final ServerRepository serverRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AllianceDetailScraperService(MapAllianceRepository allianceRepository,
                                        MapAllianceMemberRepository memberRepository,
                                        ServerRepository serverRepository) {
        this.allianceRepository = allianceRepository;
        this.memberRepository = memberRepository;
        this.serverRepository = serverRepository;
    }

    /**
     * Scrape (or fetch from cache) the detail page for one alliance. Returns
     * {@link Optional#empty()} when the alliance row doesn't exist yet (i.e.
     * the user must run a map.sql sync first so the row gets aggregated in).
     *
     * @param force {@code true} to bypass the 24h cache and re-fetch.
     */
    public Optional<MapAlliance> scrapeDetail(Long serverId, Long travianAid, boolean force) {
        if (serverId == null) throw new IllegalArgumentException("serverId is required");
        if (travianAid == null) throw new IllegalArgumentException("travianAid is required");

        // Resolve server up-front — bad ids are a 400 (IllegalArgumentException
        // -> GlobalExceptionHandler -> 400) rather than a confusing empty result.
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("Server not found: " + serverId));

        Optional<MapAlliance> existing = allianceRepository.findFirstByServerIdAndTravianAid(serverId, travianAid);
        if (existing.isEmpty()) {
            log.warn("Alliance detail scrape requested for aid={} on server {} but no MapAlliance row exists. "
                    + "Run a map.sql sync first.", travianAid, serverId);
            return Optional.empty();
        }
        MapAlliance alliance = existing.get();

        if (!force && !isStale(alliance, Instant.now())) {
            log.debug("Alliance detail cache hit for aid={} server={} (lastDetailScrapedAt={})",
                    travianAid, serverId, alliance.getLastDetailScrapedAt());
            return Optional.of(alliance);
        }

        String html = tryFetchAlliancePage(server.getUrl(), travianAid);
        if (html == null) {
            log.warn("Alliance detail scrape failed for aid={} on {} — leaving cached row untouched",
                    travianAid, server.getUrl());
            return Optional.of(alliance);
        }

        ParsedAllianceDetail parsed = AllianceDetailParser.parse(html);
        Instant now = Instant.now();
        alliance.recordDetail(
                parsed.description(),
                parsed.bonusDiplomacy(),
                parsed.bonusMining(),
                parsed.bonusScout(),
                parsed.bonusBrewery(),
                parsed.rawBonusesJson(),
                now);
        allianceRepository.save(alliance);

        // Replace roster wholesale — cheap, avoids per-row diff bookkeeping.
        memberRepository.deleteAllByMapAllianceId(alliance.getId());
        if (!parsed.members().isEmpty()) {
            List<MapAllianceMember> rows = new ArrayList<>(parsed.members().size());
            for (ParsedAllianceMember m : parsed.members()) {
                rows.add(new MapAllianceMember(
                        alliance.getId(),
                        m.uid(),
                        m.name(),
                        m.population() == null ? 0 : m.population(),
                        m.role(),
                        now));
            }
            memberRepository.saveAll(rows);
        }

        log.info("Alliance detail scraped for aid={} server={}: {} members, description={} chars",
                travianAid, server.getUrl(),
                parsed.members().size(),
                parsed.description() == null ? 0 : parsed.description().length());
        return Optional.of(alliance);
    }

    /** Convenience overload — defaults to {@code force=false}. */
    public Optional<MapAlliance> scrapeDetail(Long serverId, Long travianAid) {
        return scrapeDetail(serverId, travianAid, false);
    }

    static boolean isStale(MapAlliance alliance, Instant now) {
        Instant last = alliance.getLastDetailScrapedAt();
        if (last == null) return true;
        return Duration.between(last, now).compareTo(CACHE_TTL) > 0;
    }

    /**
     * Try the legacy {@code /allianz.php?aid=N} URL first, fall back to the
     * newer skin's {@code /alliance/N} path on 404 or obvious "not an alliance
     * page" responses. Returns {@code null} if neither URL yields a usable
     * HTML body — the caller treats that as a no-op rather than a hard error.
     */
    private String tryFetchAlliancePage(String serverUrl, Long aid) {
        String base = Server.canonicalUrl(serverUrl);
        if (base == null || base.isBlank()) return null;

        String legacy = base + "/allianz.php?aid=" + aid;
        String modern = base + "/alliance/" + aid;

        String body = fetch(legacy);
        if (body != null && looksLikeAlliancePage(body)) return body;

        log.debug("Legacy alliance URL didn't yield a usable page ({}), trying modern path", legacy);
        String body2 = fetch(modern);
        if (body2 != null && looksLikeAlliancePage(body2)) return body2;

        // Last resort: if the legacy URL at least returned HTML, hand it back —
        // the parser is tolerant enough to return empty fields rather than throw.
        return body != null ? body : body2;
    }

    /**
     * Plain anonymous HTTP fetch. Returns {@code null} on transport failure or
     * an obvious login redirect.
     * <p>
     * TODO: some Travian servers gate the alliance page behind login. The
     * planned fallback is to detect the login wall (302 to {@code /login} or
     * a "Please log in" marker in the body) and reuse a logged-in
     * {@code BrowserSessionManager} session if any account on this server is
     * currently online. Out of scope for this initial cut.
     */
    private String fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html, application/xhtml+xml, */*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            byte[] raw = response.body() == null ? new byte[0] : response.body();
            String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
            byte[] decoded = maybeDecompress(raw, encoding);
            String body = new String(decoded, StandardCharsets.UTF_8);

            if (status >= 400) {
                log.debug("Alliance fetch {} returned HTTP {}", url, status);
                return null;
            }
            if (looksLikeLoginWall(body)) {
                log.info("Alliance fetch {} hit a login wall — anonymous scrape not possible "
                        + "(TODO: fall back to a logged-in browser session)", url);
                return null;
            }
            return body;
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Alliance fetch timed out for {}: {}", url, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Alliance fetch interrupted for {}", url);
            return null;
        } catch (Exception e) {
            log.warn("Alliance fetch transport error for {}: {} - {}", url, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private byte[] maybeDecompress(byte[] raw, String contentEncoding) {
        boolean headerSaysGzip = contentEncoding.contains("gzip");
        boolean magicSaysGzip = raw.length >= 2
                && (raw[0] & 0xFF) == 0x1f
                && (raw[1] & 0xFF) == 0x8b;
        if (!headerSaysGzip && !magicSaysGzip) return raw;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4)) {
            gz.transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Gzip decode failed on alliance fetch (encoding='{}', magic={}): {}",
                    contentEncoding, magicSaysGzip, e.getMessage());
            return raw;
        }
    }

    /**
     * Loose marker for a usable alliance detail page. We look for an HTML
     * document containing either the legacy "allianz" / "Alliance" header or
     * a recognisable members table. Returns false for landing pages and
     * obvious error placeholders.
     */
    static boolean looksLikeAlliancePage(String body) {
        if (body == null || body.length() < 50) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        boolean hasAllianceKeyword = lower.contains("allianz")
                || lower.contains("alliance")
                || lower.contains("alianza");
        boolean hasTableMarker = lower.contains("<table")
                || lower.contains("members")
                || lower.contains("mitglieder");
        return hasAllianceKeyword && hasTableMarker;
    }

    /**
     * Detect a login-wall page so we don't try to parse the login form as an
     * alliance roster. Travian renders the wall as a plain login form with
     * a recognisable name field; sometimes as a flash message asking the user
     * to log in.
     */
    static boolean looksLikeLoginWall(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("please log in")
                || lower.contains("bitte einloggen")
                || (lower.contains("name=\"user\"") && lower.contains("name=\"password\""));
    }
}
