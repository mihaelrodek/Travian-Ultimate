package com.travianbot.server.discovery;

import java.util.List;

/**
 * Result envelope from {@link ServerDiscoveryService#discoverAll()}.
 * <p>
 * {@code probed} counts every URL fanned out to the executor, including dead
 * ones; {@code discovered} is the subset that returned a valid {@code map.sql}
 * AND was new to our DB; {@code alreadyKnown} is the subset that was live but
 * we already had a {@code Server} row for. {@code probed - discovered -
 * alreadyKnown} therefore equals the dead-URL count.
 */
public record DiscoveryReport(
        int probed,
        int discovered,
        int alreadyKnown,
        long durationMs,
        List<String> discoveredUrls
) {
}
