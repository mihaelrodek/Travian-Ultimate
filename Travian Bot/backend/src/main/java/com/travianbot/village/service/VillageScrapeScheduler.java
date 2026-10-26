package com.travianbot.village.service;

import com.travianbot.account.service.ActiveWindowGate;
import com.travianbot.account.service.SessionRecoveryService;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.scraper.NotLoggedInException;
import com.travianbot.bot.scraper.ScrapingService;
import com.travianbot.settings.service.GeneralSettingsService;
import com.travianbot.village.domain.Village;
import com.travianbot.village.domain.VillageSettings;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.persistence.VillageSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodic tick that re-scrapes every village whose per-village
 * {@code autoScrapeIntervalMinutes} has elapsed since its last successful scrape.
 * <p>
 * Previously a single global {@code villageScrapeIntervalMinutes} on
 * {@code GeneralSettings} drove this — replaced because real play wants per-village
 * pacing (a capital under attack needs 2–5 min polling while a quiet support
 * village is fine at 30–60).
 * <p>
 * Guards:
 * <ul>
 *   <li>{@code autoScrapeEnabled} — user can mute a village entirely so it
 *       doesn't fight with a UI that's actively scraping it.</li>
 *   <li>{@code sessionManager.hasOpenSession(accountId)} — by default no login
 *       is triggered from the scheduler; the village goes quiet until the user
 *       comes back. With the opt-in {@code autoReloginEnabled} setting, a lost
 *       session instead goes through {@link SessionRecoveryService} (one
 *       cooldown-limited login attempt) before giving up.</li>
 *   <li>{@code travianVillageId != null} — can't target a village without a
 *       did; silently skip those (the sidebar scrape will eventually fill it).</li>
 * </ul>
 * Any per-village scrape failure is caught and logged at WARN — one sick village
 * can't stall the rest of the rotation.
 */
@Component
public class VillageScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(VillageScrapeScheduler.class);

    private final VillageRepository villageRepository;
    private final VillageSettingsRepository settingsRepository;
    private final BrowserSessionManager sessionManager;
    private final ScrapingService scrapingService;
    private final ActiveWindowGate activeWindowGate;
    private final GeneralSettingsService generalSettingsService;
    private final SessionRecoveryService sessionRecoveryService;

    public VillageScrapeScheduler(
            VillageRepository villageRepository,
            VillageSettingsRepository settingsRepository,
            BrowserSessionManager sessionManager,
            ScrapingService scrapingService,
            ActiveWindowGate activeWindowGate,
            GeneralSettingsService generalSettingsService,
            SessionRecoveryService sessionRecoveryService
    ) {
        this.villageRepository = villageRepository;
        this.settingsRepository = settingsRepository;
        this.sessionManager = sessionManager;
        this.scrapingService = scrapingService;
        this.activeWindowGate = activeWindowGate;
        this.generalSettingsService = generalSettingsService;
        this.sessionRecoveryService = sessionRecoveryService;
    }

    /**
     * Wake up every minute. One minute is the finest resolution the UI exposes
     * (the interval field accepts integer minutes), so polling any faster would
     * just burn CPU without affecting timing accuracy.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void tick() {
        // Hard kill-switch — user can disable every scheduled village scrape
        // globally from the Settings page. Manual UI scrapes still work
        // because they don't run through this scheduler.
        if (!generalSettingsService.get().isVillageScrapingEnabled()) {
            log.debug("VillageScrapeScheduler: globally disabled — skipping tick");
            return;
        }
        List<DueEntry> due = findDue();
        if (due.isEmpty()) return;

        log.info("VillageScrapeScheduler: {} village(s) due this tick", due.size());
        for (DueEntry entry : due) {
            // Account-wide "active hours" gate FIRST — it must also veto
            // auto-relogin, otherwise a lost session would trigger a login at
            // 3 am on an account whose window says 08:00–22:00. Manual UI
            // scrapes still go through (they bypass the scheduler entirely).
            if (!activeWindowGate.isAllowedNow(entry.accountId())) {
                log.debug("Skipping village {} — account {} outside active window",
                        entry.villageId(), entry.accountId());
                continue;
            }
            if (!sessionManager.hasOpenSession(entry.accountId())) {
                // Opt-in recovery: one cooldown-limited login attempt; when
                // disabled or still cooling down this returns false and the
                // village stays quiet, exactly like the pre-P1.7 behaviour.
                if (!sessionRecoveryService.tryRelogin(entry.accountId(),
                        "scheduled scrape found no open session")) {
                    log.debug("Skipping village {} — no open browser session for account {}",
                            entry.villageId(), entry.accountId());
                    continue;
                }
            }
            try {
                scrapeWithRetry(entry);
            } catch (Exception e) {
                // WARN not ERROR — a single failed scrape is expected during
                // normal play (account just logged off, maintenance window, etc.)
                // and doesn't warrant surfacing higher.
                log.warn("Scheduled scrape failed for village {} (account {}): {}",
                        entry.villageId(), entry.accountId(), e.getMessage());
            }
        }
    }

    /**
     * One scrape, with a single auto-relogin retry if the session evaporated
     * between the {@code hasOpenSession} check and the actual page fetch
     * (server-side logout mid-rotation). A second {@link NotLoggedInException}
     * propagates to the caller's catch — no loops.
     */
    private void scrapeWithRetry(DueEntry entry) {
        try {
            scrapingService.scrapeVillage(entry.accountId(), entry.villageId());
        } catch (NotLoggedInException e) {
            if (!sessionRecoveryService.tryRelogin(entry.accountId(),
                    "session lost mid-scrape for village " + entry.villageId())) {
                throw e;
            }
            scrapingService.scrapeVillage(entry.accountId(), entry.villageId());
        }
    }

    /**
     * Walks all settings rows + their villages, returning a list of
     * {accountId, villageId} pairs that are due now.
     * <p>
     * Read-only transaction so the lazy {@code village} association resolves
     * without throwing {@link org.hibernate.LazyInitializationException}.
     */
    @Transactional(readOnly = true)
    protected List<DueEntry> findDue() {
        Instant now = Instant.now();
        List<VillageSettings> all = settingsRepository.findAll();
        if (all.isEmpty()) return List.of();

        // Map villageId -> Village to avoid N+1 if the lazy association has
        // already been initialised; falls back to settings.getVillage() otherwise.
        Map<Long, Village> villageById = new HashMap<>();
        List<Long> villageIds = new ArrayList<>();
        for (VillageSettings s : all) villageIds.add(s.getVillage().getId());
        for (Village v : villageRepository.findAllById(villageIds)) villageById.put(v.getId(), v);

        List<DueEntry> out = new ArrayList<>();
        for (VillageSettings s : all) {
            if (!s.isAutoScrapeEnabled()) continue;
            int minutes = s.getAutoScrapeIntervalMinutes();
            if (minutes <= 0) continue;

            Village v = villageById.get(s.getVillage().getId());
            if (v == null) continue;
            if (v.getTravianVillageId() == null) continue;

            Instant last = v.getLastScrapedAt();
            if (last != null && last.plus(Duration.ofMinutes(minutes)).isAfter(now)) continue;

            out.add(new DueEntry(v.getAccount().getId(), v.getId()));
        }
        return out;
    }

    /** Tiny record — keeps the scheduler itself allocation-light and easy to unit-test. */
    protected record DueEntry(Long accountId, Long villageId) {}
}
