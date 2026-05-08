package com.travianbot.statistics.api;

import com.travianbot.statistics.api.dto.HistoryResponse;
import com.travianbot.statistics.api.dto.Top10Response;
import com.travianbot.statistics.domain.StatCategory;
import com.travianbot.statistics.scraper.StatisticsScrapingService;
import com.travianbot.statistics.service.StatisticsHeartbeatRegistry;
import com.travianbot.statistics.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

/**
 * HTTP endpoints for the statistics feature. All four {@link StatCategory}
 * tables live on the same {@code /statistics/player/top10} page, so one
 * capture yields one snapshot per category — the read endpoints take a
 * {@code ?category=} parameter so each tab in the UI gets its own response.
 * <ul>
 *   <li>{@code GET /api/statistics/top10?accountId=X&category=PVP} — latest
 *       top-10 for one category with deltas against the previous capture.</li>
 *   <li>{@code GET /api/statistics/history?accountId=X&category=PVP&hours=N&player=...}
 *       — hourly history window for a chart. {@code player} is optional; when
 *       omitted the points are aggregates across the whole top-10.</li>
 *   <li>{@code POST /api/statistics/capture?accountId=X} — force a capture
 *       now. Returns the just-persisted snapshots for all four categories so
 *       the UI can update every tab in one round-trip.</li>
 *   <li>{@code POST /api/statistics/heartbeat?accountId=X} — UI tells the
 *       backend "I'm looking at the stats page for this account" so the
 *       hourly scheduler includes it in its next tick.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final StatisticsScrapingService scrapingService;
    private final StatisticsHeartbeatRegistry heartbeat;

    public StatisticsController(
            StatisticsService statisticsService,
            StatisticsScrapingService scrapingService,
            StatisticsHeartbeatRegistry heartbeat
    ) {
        this.statisticsService = statisticsService;
        this.scrapingService = scrapingService;
        this.heartbeat = heartbeat;
    }

    @GetMapping("/top10")
    public Top10Response top10(
            @RequestParam Long accountId,
            @RequestParam(required = false, defaultValue = "PVP") StatCategory category
    ) {
        return statisticsService.getTop10(accountId, category);
    }

    @GetMapping("/history")
    public HistoryResponse history(
            @RequestParam Long accountId,
            @RequestParam(required = false, defaultValue = "PVP") StatCategory category,
            @RequestParam(required = false, defaultValue = "12") int hours,
            @RequestParam(required = false, name = "player") String playerName
    ) {
        return statisticsService.getHistory(accountId, category, hours, playerName);
    }

    /**
     * Force an immediate capture. Scrapes the top-10 page once and returns the
     * persisted snapshot per category — the response mirrors the tabs in the
     * UI so a single click can refresh everything. Categories whose table
     * happened to be missing from the page are absent from the map.
     */
    @PostMapping("/capture")
    public Map<StatCategory, Top10Response> capture(@RequestParam Long accountId) {
        // Trigger the scrape + persist, then re-query through the read service
        // so the response carries deltas against the previous snapshot (the
        // scraper returns just the latest, without delta context).
        scrapingService.capture(accountId);
        Map<StatCategory, Top10Response> out = new EnumMap<>(StatCategory.class);
        for (StatCategory c : StatCategory.values()) {
            out.put(c, statisticsService.getTop10(accountId, c));
        }
        return out;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat(@RequestParam Long accountId) {
        heartbeat.ping(accountId);
        return ResponseEntity.noContent().build();
    }
}
