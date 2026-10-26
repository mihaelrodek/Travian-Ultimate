package com.travianbot.farmlist.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodic tick that walks every account with an open browser session and
 * scrapes any new raid reports.
 * <p>
 * Conservative cadence — every 10 minutes. Travian's report list is paginated
 * and reports persist for ~30 days, so the bot doesn't need a finer interval
 * to avoid missing anything; a tighter interval would just spam the report
 * page with no analytical benefit.
 * <p>
 * Like every other scheduler in this codebase, an individual scrape failure
 * is caught and logged at WARN — one sick account can't stall the rest of
 * the rotation. Accounts without an open browser session are silently
 * skipped: the scheduler never triggers a login on its own.
 */
@Component
public class RaidReportScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RaidReportScrapeScheduler.class);

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final RaidReportScraperService scraperService;

    public RaidReportScrapeScheduler(BrowserSessionManager sessionManager,
                                     AccountRepository accountRepository,
                                     RaidReportScraperService scraperService) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.scraperService = scraperService;
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L)
    public void tick() {
        List<Account> accounts = accountRepository.findAll();
        if (accounts.isEmpty()) return;

        for (Account account : accounts) {
            Long accountId = account.getId();
            if (!sessionManager.hasOpenSession(accountId)) continue;
            try {
                int saved = scraperService.scrapeForAccount(accountId);
                if (saved > 0) {
                    log.info("RaidReportScrapeScheduler: persisted {} new reports for account {}",
                            saved, accountId);
                }
            } catch (Exception e) {
                log.warn("Raid-report scrape failed for account {}: {}", accountId, e.getMessage());
            }
        }
    }
}
