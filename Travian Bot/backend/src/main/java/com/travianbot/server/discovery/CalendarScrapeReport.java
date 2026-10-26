package com.travianbot.server.discovery;

/**
 * Result envelope from {@link CalendarScraperService#scrape()}.
 * <p>
 * {@code parsed} is the count of DOM entries we successfully extracted;
 * {@code created} are newly-inserted {@code UpcomingServer} rows;
 * {@code updated} are existing rows refreshed via the {@code calendarId}
 * lookup. The sum may exceed {@code parsed} in pathological cases (selector
 * fallback double-counting) — that's a smell worth a follow-up but never a
 * correctness issue, since both paths go through the unique-key upsert.
 */
public record CalendarScrapeReport(
        int parsed,
        int created,
        int updated,
        long durationMs
) {
}
