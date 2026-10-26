package com.travianbot.server.api.dto;

import com.travianbot.server.discovery.DiscoveryReport;

import java.util.List;

/**
 * Wire shape for {@link com.travianbot.server.discovery.DiscoveryReport}.
 * <p>
 * Kept separate from the domain record so future fields (e.g. per-probe
 * timing histograms) can be added without breaking the API contract.
 */
public record DiscoveryReportResponse(
        int probed,
        int discovered,
        int alreadyKnown,
        long durationMs,
        List<String> discoveredUrls
) {
    public static DiscoveryReportResponse from(DiscoveryReport r) {
        return new DiscoveryReportResponse(
                r.probed(),
                r.discovered(),
                r.alreadyKnown(),
                r.durationMs(),
                r.discoveredUrls()
        );
    }
}
