package com.travianbot.enemyintel.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Two periodic ticks that keep the enemy-intel module fresh.
 * <ul>
 *   <li><b>Resolver tick</b> ({@code @Scheduled(fixedDelay = 5min)}) — turns
 *       {@code IncomingAttack#attackerName} values into {@link
 *       com.travianbot.enemyintel.domain.EnemyPlayer} rows for every account
 *       with a live browser session. The 48-hour resolution window covers
 *       the typical "I just got attacked, who did it?" investigative loop
 *       without re-resolving ancient history every tick.</li>
 *   <li><b>Scraper tick</b> ({@code @Scheduled(fixedDelay = 1h)}) — refreshes
 *       up to 10 stale enemies per account per hour. "Stale" = the row's
 *       {@code lastScrapedAt} is older than 6h. Capping per-account
 *       protects against an account with hundreds of enemies hammering the
 *       same Travian server.</li>
 * </ul>
 * Mirrors {@code VillageScrapeScheduler}: never opens a session, never logs
 * in, swallows per-account failures so one sick account doesn't stall the
 * rest of the rotation.
 */
@Component
public class EnemyIntelScheduler {

    private static final Logger log = LoggerFactory.getLogger(EnemyIntelScheduler.class);

    private static final Duration RESOLVER_WINDOW = Duration.ofHours(48);
    private static final Duration SCRAPER_STALENESS = Duration.ofHours(6);
    private static final int SCRAPER_MAX_PER_RUN = 10;

    private final EnemyResolverService resolverService;
    private final EnemyProfileScraperService scraperService;
    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;

    public EnemyIntelScheduler(
            EnemyResolverService resolverService,
            EnemyProfileScraperService scraperService,
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository
    ) {
        this.resolverService = resolverService;
        this.scraperService = scraperService;
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
    }

    /**
     * Five-minute resolver tick — newly-scraped attacks land an EnemyPlayer
     * row inside one cycle, which is plenty fast for the "who is this?" UX
     * without churning the DB every minute.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void registerFromRecentAttacksTick() {
        for (Account account : accountRepository.findAll()) {
            Long id = account.getId();
            if (!sessionManager.hasOpenSession(id)) continue;
            try {
                int created = resolverService.registerFromRecentAttacks(id, RESOLVER_WINDOW);
                if (created > 0) {
                    log.info("EnemyIntel resolver: {} new enemy(ies) for account {}", created, id);
                }
            } catch (Exception e) {
                // WARN — a single account-level resolver hiccup must not
                // abort the rest of the loop. Most failures here are "no
                // map.sql yet" which the service handles gracefully.
                log.warn("EnemyIntel resolver tick failed for account {}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Hourly scraper tick with a 10-minute initial delay so we don't try to
     * scrape inside the first couple of post-boot minutes (browser sessions
     * may not be open yet, login is still re-establishing).
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void scraperTick() {
        for (Account account : accountRepository.findAll()) {
            Long id = account.getId();
            if (!sessionManager.hasOpenSession(id)) continue;
            try {
                int scraped = scraperService.refreshStaleForAccount(
                        id, SCRAPER_STALENESS, SCRAPER_MAX_PER_RUN);
                if (scraped > 0) {
                    log.info("EnemyIntel scraper: refreshed {} enemy profile(s) for account {}",
                            scraped, id);
                }
            } catch (Exception e) {
                log.warn("EnemyIntel scraper tick failed for account {}: {}", id, e.getMessage());
            }
        }
    }
}
