package com.travianbot.intel.service;

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
import java.util.zip.GZIPInputStream;

/**
 * Pulls the daily public {@code map.sql} dump from a Travian server.
 * <p>
 * Design notes (learned the hard way):
 * <ul>
 *   <li><b>Realistic User-Agent</b> — Travian's CDN drops any UA containing
 *       "bot" with a 403. We pose as a recent Chrome on Windows.</li>
 *   <li><b>Gzip decode</b> — Travian usually serves map.sql gzipped (it's
 *       multi-MB). {@link HttpClient} does NOT auto-decompress, so we sniff
 *       both the {@code Content-Encoding} header AND the gzip magic bytes,
 *       then inflate by hand.</li>
 *   <li><b>Body preview in errors</b> — non-200 responses often return HTML
 *       (Cloudflare challenge, login wall, gameworld start page). We embed
 *       the first 300 chars of the body in the exception so the bot-event log
 *       is self-diagnosing instead of just "HTTP 403".</li>
 *   <li><b>Sanity check</b> — if a 200 body doesn't contain {@code "INSERT"}
 *       anywhere, treat it as a failure with the body preview attached
 *       (catches "we got the homepage instead of the dump" silently).</li>
 *   <li><b>Generous timeout</b> — multi-MB dump + slow servers; 60s.</li>
 * </ul>
 */
@Service
public class MapSqlFetcher {

    private static final Logger log = LoggerFactory.getLogger(MapSqlFetcher.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** Modern Chrome UA — Travian accepts it without challenge. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    /** How many body bytes to embed in error messages. */
    private static final int BODY_PREVIEW_CHARS = 300;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Fetch {@code <serverUrl>/map.sql} and return the response body as text.
     *
     * @param serverUrl the Travian server base URL (no trailing slash required)
     * @return the raw SQL dump text (decompressed if gzipped)
     * @throws MapSqlFetchException on non-200, timeout, transport error, or
     *         a 200 body that doesn't look like a SQL dump
     */
    public String fetch(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new MapSqlFetchException(String.valueOf(serverUrl), "server URL is blank");
        }
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        String target = base + "/map.sql";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain, application/sql, */*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

        try {
            log.info("Fetching map.sql from {}", target);
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            byte[] raw = response.body() == null ? new byte[0] : response.body();
            String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
            byte[] decoded = maybeDecompress(raw, encoding);

            if (status != 200) {
                String preview = previewOf(decoded);
                throw new MapSqlFetchException(serverUrl,
                        "HTTP " + status + " from " + target
                                + " · " + decoded.length + " bytes · preview: " + preview);
            }

            String body = new String(decoded, StandardCharsets.UTF_8);
            if (!looksLikeSqlDump(body)) {
                String preview = previewOf(decoded);
                throw new MapSqlFetchException(serverUrl,
                        "200 OK but body is not a SQL dump (no INSERT keyword found). "
                                + "Body was " + decoded.length + " bytes · preview: " + preview);
            }
            log.info("Fetched {} bytes of map.sql from {} (raw {} bytes, encoding='{}')",
                    body.length(), target, raw.length, encoding.isBlank() ? "none" : encoding);
            return body;
        } catch (MapSqlFetchException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new MapSqlFetchException(serverUrl,
                    "timeout after " + TIMEOUT.toSeconds() + "s fetching " + target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MapSqlFetchException(serverUrl, "interrupted fetching " + target, e);
        } catch (Exception e) {
            throw new MapSqlFetchException(serverUrl,
                    "transport error " + e.getClass().getSimpleName() + ": "
                            + (e.getMessage() == null ? "(no message)" : e.getMessage()) + " · " + target, e);
        }
    }

    /**
     * Decompress if the response is gzip-encoded — checks both the header AND
     * the gzip magic bytes (0x1f 0x8b) so we still handle servers that send
     * the gzip stream without setting {@code Content-Encoding}.
     */
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
            // Gzip decode failed — return the raw bytes so the caller's sanity
            // check produces a sensible error preview rather than us swallowing it.
            log.warn("Gzip decode failed (encoding header='{}', magic={}): {}",
                    contentEncoding, magicSaysGzip, e.getMessage());
            return raw;
        }
    }

    /**
     * Cheap sanity check — a real {@code map.sql} always contains at least one
     * {@code INSERT} keyword. Anything else (HTML landing page, JSON error,
     * empty body) fails this test.
     */
    private boolean looksLikeSqlDump(String body) {
        if (body == null || body.length() < 20) return false;
        // Case-insensitive contains without allocating a copy.
        int len = body.length();
        for (int i = 0; i + 6 <= len; i++) {
            if ((body.charAt(i) | 0x20) == 'i'
                    && (body.charAt(i + 1) | 0x20) == 'n'
                    && (body.charAt(i + 2) | 0x20) == 's'
                    && (body.charAt(i + 3) | 0x20) == 'e'
                    && (body.charAt(i + 4) | 0x20) == 'r'
                    && (body.charAt(i + 5) | 0x20) == 't') {
                return true;
            }
        }
        return false;
    }

    /**
     * Build a single-line preview of the body, trimmed + length-capped, suitable
     * for embedding in an exception message or log line.
     */
    private String previewOf(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (text.length() > BODY_PREVIEW_CHARS) {
            text = text.substring(0, BODY_PREVIEW_CHARS) + "…";
        }
        return text.isEmpty() ? "(empty body)" : text;
    }
}
