package com.travianbot.server.api.dto;

import com.travianbot.server.discovery.CalendarScrapeReport;

/**
 * Wire shape for {@link CalendarScrapeReport}. Mirrors the record exactly —
 * kept as a separate type so the API contract is the wire boundary, not the
 * service-layer record.
 */
public record CalendarScrapeReportResponse(
        int parsed,
        int created,
        int updated,
        long durationMs
) {
    public static CalendarScrapeReportResponse from(CalendarScrapeReport r) {
        return new CalendarScrapeReportResponse(r.parsed(), r.created(), r.updated(), r.durationMs());
    }
}
