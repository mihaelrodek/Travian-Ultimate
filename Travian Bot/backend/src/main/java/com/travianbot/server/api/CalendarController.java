package com.travianbot.server.api;

import com.travianbot.server.api.dto.CalendarScrapeReportResponse;
import com.travianbot.server.api.dto.UpcomingServerResponse;
import com.travianbot.server.discovery.CalendarScrapeReport;
import com.travianbot.server.discovery.CalendarScraperService;
import com.travianbot.server.domain.UpcomingServer;
import com.travianbot.server.persistence.UpcomingServerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * REST surface for the upcoming-server calendar.
 * <p>
 * {@code GET /upcoming} is cheap (single DB read, no scraping); the {@code
 * POST /refresh} trigger fires the Playwright scraper inline and blocks until
 * complete — same pattern as {@link ServerDiscoveryController}, fine for a
 * manual user action but not for automation.
 */
@RestController
@RequestMapping("/api/servers/calendar")
public class CalendarController {

    private final CalendarScraperService calendarScraperService;
    private final UpcomingServerRepository upcomingServerRepository;

    public CalendarController(CalendarScraperService calendarScraperService,
                              UpcomingServerRepository upcomingServerRepository) {
        this.calendarScraperService = calendarScraperService;
        this.upcomingServerRepository = upcomingServerRepository;
    }

    /**
     * List every upcoming launch, oldest start-date first. When the
     * {@code status} query parameter is set, only rows whose status is in the
     * comma-separated set are returned — used by the UI to default to
     * "ANNOUNCED,OPEN_REGISTRATION" and hide stale ENDED rows.
     */
    @GetMapping("/upcoming")
    public List<UpcomingServerResponse> listUpcoming(@RequestParam(required = false) String status) {
        List<UpcomingServer> rows = (status == null || status.isBlank())
                ? upcomingServerRepository.findAllByOrderByScheduledStartAtAsc()
                : upcomingServerRepository.findAllByStatusInOrderByScheduledStartAtAsc(parseStatuses(status));
        return rows.stream().map(UpcomingServerResponse::from).toList();
    }

    /** Trigger a fresh Playwright scrape and return the count summary. */
    @PostMapping("/refresh")
    public CalendarScrapeReportResponse refresh() {
        CalendarScrapeReport report = calendarScraperService.scrape();
        return CalendarScrapeReportResponse.from(report);
    }

    private static Set<String> parseStatuses(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : Arrays.asList(raw.split(","))) {
            String t = s == null ? null : s.trim().toUpperCase();
            if (t != null && !t.isEmpty()) out.add(t);
        }
        return out;
    }
}
