package com.travianbot.incoming.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes every 90 seconds and scrapes the rally-point incoming-attacks page
 * for every account that currently has an open browser session.
 * <p>
 * Mirrors the shape of {@code VillageScrapeScheduler}: never opens a new
 * session, never logs in, never lets one bad account stall the rest of the
 * loop. The 90-second cadence is a compromise — fast enough that a fresh
 * incoming is noticed inside the typical 2-minute "are we under attack?"
 * panic window, slow enough that we don't hammer the server (rally-point is
 * one of the cheaper Travian pages, but politeness still matters).
 */
@Component
public class IncomingAttackScheduler {

    private static final Logger log = LoggerFactory.getLogger(IncomingAttackScheduler.class);

    private final IncomingAttackService incomingAttackService;
    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;

    public IncomingAttackScheduler(
            IncomingAttackService incomingAttackService,
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository
    ) {
        this.incomingAttackService = incomingAttackService;
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
    }

    @Scheduled(fixedDelay = 90_000L, initialDelay = 30_000L)
    public void tick() {
        for (Account account : accountRepository.findAll()) {
            Long id = account.getId();
            if (!sessionManager.hasOpenSession(id)) continue;
            try {
                int found = incomingAttackService.scrapeForAccount(id);
                if (found > 0) {
                    log.info("Incoming-attack scheduler: {} new wave(s) for account {}", found, id);
                }
            } catch (Exception e) {
                // WARN — a single account-level failure (not logged in, page
                // 500, etc.) must not abort the rest of the rotation.
                log.warn("Incoming scrape failed for account {}: {}", id, e.getMessage());
            }
        }
    }
}
