package com.travianbot.server.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly trigger for the calendar scraper.
 * <p>
 * Cron is Monday 03:30 server-time — Travian rarely touches their
 * announcement page outside Tuesday afternoons (EU office hours), so picking
 * up Monday gives us a fresh snapshot just before the typical announcement
 * cycle without competing for the bot's daytime browser sessions.
 * <p>
 * Failures are caught and logged at WARN so a single broken scrape can't take
 * the scheduler down — mirrors {@link com.travianbot.village.service.VillageScrapeScheduler}'s
 * defensive shape.
 */
@Component
public class CalendarScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(CalendarScrapeScheduler.class);

    private final CalendarScraperService calendarScraperService;

    public CalendarScrapeScheduler(CalendarScraperService calendarScraperService) {
        this.calendarScraperService = calendarScraperService;
    }

    /** {@code 0 30 3 * * MON} — every Monday at 03:30. */
    @Scheduled(cron = "0 30 3 * * MON")
    public void tick() {
        try {
            CalendarScrapeReport report = calendarScraperService.scrape();
            log.info("CalendarScrapeScheduler: parsed={} created={} updated={} durationMs={}",
                    report.parsed(), report.created(), report.updated(), report.durationMs());
        } catch (Exception e) {
            log.warn("CalendarScrapeScheduler: scrape failed — {}", e.getMessage(), e);
        }
    }
}
