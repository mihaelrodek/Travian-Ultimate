package com.travianbot.farmlist.api;

import com.travianbot.farmlist.api.dto.RaidReportResponse;
import com.travianbot.farmlist.service.RaidReportScraperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Endpoints behind the "raid analytics" tab — let the user trigger an
 * immediate scrape and inspect the raw report rows we've collected.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/raid-reports")
public class RaidReportController {

    private final RaidReportScraperService scraperService;

    public RaidReportController(RaidReportScraperService scraperService) {
        this.scraperService = scraperService;
    }

    /** Fire an immediate scrape — the response carries the number of newly-persisted reports. */
    @PostMapping("/scrape")
    public ScrapeResponse scrape(@PathVariable Long accountId) {
        int saved = scraperService.scrapeForAccount(accountId);
        return new ScrapeResponse(saved);
    }

    /** List reports attributed to a specific farmlist target, latest first. */
    @GetMapping("/by-target/{farmlistTargetId}")
    public List<RaidReportResponse> byTarget(@PathVariable Long accountId,
                                             @PathVariable Long farmlistTargetId) {
        return scraperService.listFor(accountId, farmlistTargetId).stream()
                .map(RaidReportResponse::from)
                .toList();
    }

    /**
     * Recent reports across the whole account. {@code windowMinutes} defaults
     * to 60 — pass an explicit value to widen or narrow the lookback.
     */
    @GetMapping("/recent")
    public List<RaidReportResponse> recent(@PathVariable Long accountId,
                                           @RequestParam(defaultValue = "60") long windowMinutes) {
        long safeMinutes = Math.max(1L, windowMinutes);
        return scraperService.listRecent(accountId, Duration.ofMinutes(safeMinutes)).stream()
                .map(RaidReportResponse::from)
                .toList();
    }

    public record ScrapeResponse(int scraped) {}
}
