package com.travianbot.statistics.service;

import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.statistics.scraper.StatisticsScrapingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Captures a fresh top-10 snapshot once an hour for every account whose
 * statistics page is currently being watched in the UI (see
 * {@link StatisticsHeartbeatRegistry}). The UI-gate is load-bearing — the
 * scheduler would otherwise keep opening the statistics page for dormant
 * accounts nobody is looking at, which wastes Playwright cycles and makes
 * the bot's behavior noisier from the server's perspective.
 * <p>
 * Fires sequentially across accounts rather than in parallel: Playwright
 * pages are single-threaded per client, and even across accounts we'd rather
 * spread the load than spike CPU once an hour.
 */
@Component
public class StatisticsCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatisticsCaptureScheduler.class);

    private final StatisticsScrapingService scrapingService;
    private final StatisticsHeartbeatRegistry heartbeat;
    private final BrowserSessionManager sessionManager;

    public StatisticsCaptureScheduler(
            StatisticsScrapingService scrapingService,
            StatisticsHeartbeatRegistry heartbeat,
            BrowserSessionManager sessionManager
    ) {
        this.scrapingService = scrapingService;
        this.heartbeat = heartbeat;
        this.sessionManager = sessionManager;
    }

    /**
     * Exactly one hour between captures. We use {@code fixedDelay} (not
     * {@code fixedRate}) so a slow capture doesn't cause back-to-back
     * captures to stack up behind it.
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void tick() {
        Set<Long> active = heartbeat.activeAccounts();
        if (active.isEmpty()) return;
        log.info("StatisticsCaptureScheduler: {} active account(s) to capture", active.size());

        for (Long accountId : active) {
            if (!sessionManager.hasOpenSession(accountId)) {
                log.debug("Skipping stats capture for account {} — no browser session", accountId);
                continue;
            }
            try {
                scrapingService.capture(accountId);
            } catch (Exception e) {
                log.warn("Stats capture failed for account {}: {}", accountId, e.getMessage());
            }
        }
    }
}
