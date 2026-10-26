package com.travianbot.enemyintel.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.ProfileScraper;
import com.travianbot.bot.scraper.ProfileScraper.EnemyProfileScrape;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.enemyintel.domain.EnemyPlayer;
import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;
import com.travianbot.enemyintel.persistence.EnemyPlayerRepository;
import com.travianbot.enemyintel.persistence.EnemyProfileSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Periodically scrapes enemy players' public profile pages and updates both
 * the {@link EnemyPlayer} summary row and the append-only
 * {@link EnemyProfileSnapshot} timeline.
 * <p>
 * The class is deliberately defensive: one bad profile page (Travian
 * maintenance, an unrecognised skin, a sitter-hidden hero panel) must not
 * abort the loop. Every per-enemy scrape sits inside its own try/catch and a
 * failed scrape is logged at WARN but doesn't propagate.
 */
@Service
@Transactional
public class EnemyProfileScraperService {

    private static final Logger log = LoggerFactory.getLogger(EnemyProfileScraperService.class);

    private static final String EVENT_ACTION = "ENEMY_PROFILE_SCRAPE";

    /**
     * Scraper-side timeout for the profile page selector wait — the same 5s
     * the existing tribe-auto-detect uses on the bot's own /profile page.
     */
    private static final int PROFILE_WAIT_MS = 5_000;

    private final EnemyPlayerRepository enemyPlayerRepository;
    private final EnemyProfileSnapshotRepository snapshotRepository;
    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final BotEventService botEventService;
    private final EnemyChangeDetector changeDetector;

    public EnemyProfileScraperService(
            EnemyPlayerRepository enemyPlayerRepository,
            EnemyProfileSnapshotRepository snapshotRepository,
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            BotEventService botEventService,
            EnemyChangeDetector changeDetector
    ) {
        this.enemyPlayerRepository = enemyPlayerRepository;
        this.snapshotRepository = snapshotRepository;
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.botEventService = botEventService;
        this.changeDetector = changeDetector;
    }

    /**
     * Scrape a single enemy's public profile. Returns the saved snapshot, or
     * empty if no fields could be parsed (we treat that as "we tried, came
     * back with nothing — don't write an empty snapshot to pollute the
     * timeline"). Even on an empty scrape we still stamp
     * {@link EnemyPlayer#getLastScrapedAt()} so the staleness check rotates
     * — otherwise a broken profile would pin the scheduler to the same row
     * forever.
     */
    public Optional<EnemyProfileSnapshot> scrape(Long enemyPlayerId) {
        EnemyPlayer player = enemyPlayerRepository.findById(enemyPlayerId).orElse(null);
        if (player == null) {
            log.debug("EnemyProfileScraper: skipped — no EnemyPlayer for id {}", enemyPlayerId);
            return Optional.empty();
        }
        BrowserTravianClient client = sessionManager.existing(player.getAccountId());
        if (client == null || !client.session().isLoggedIn()) {
            log.debug("EnemyProfileScraper: account {} not logged in; skipping enemy {}",
                    player.getAccountId(), enemyPlayerId);
            return Optional.empty();
        }
        Account account = accountRepository.findById(player.getAccountId()).orElse(null);
        if (account == null) {
            log.debug("EnemyProfileScraper: account {} missing; skipping enemy {}",
                    player.getAccountId(), enemyPlayerId);
            return Optional.empty();
        }

        String serverUrl = trimTrailingSlash(account.getServerUrl());
        String html = tryFetchProfile(client, serverUrl, player.getTravianUid());
        if (html == null) {
            // Still stamp lastScrapedAt — see method javadoc.
            player.recordScrape(player.getHeroLevel(), player.getHeroXp(), player.getHeroProductionBonus(),
                    player.getCurrentPopulation(), player.getVillageCount(),
                    player.getAllianceTravianId(), player.getAllianceName());
            enemyPlayerRepository.save(player);
            botEventService.record(player.getAccountId(), null, EVENT_ACTION,
                    BotEventOutcome.WARN,
                    "Enemy profile fetch failed for uid " + player.getTravianUid());
            return Optional.empty();
        }

        EnemyProfileScrape parsed;
        try {
            parsed = ProfileScraper.parseEnemyProfile(html);
        } catch (RuntimeException e) {
            log.warn("EnemyProfileScraper: parse failure for enemy {} uid {}: {}",
                    enemyPlayerId, player.getTravianUid(), e.getMessage());
            return Optional.empty();
        }

        // Build the snapshot. If every parsed field is null we abort early —
        // recording an all-null row would pollute the timeline AND fool the
        // change detector into thinking the player "lost everything".
        EnemyProfileSnapshot snap = new EnemyProfileSnapshot(player.getId(), Instant.now());
        snap.setHeroLevel(parsed.heroLevel());
        snap.setHeroXp(parsed.heroXp());
        snap.setHeroProductionBonus(parsed.heroProductionBonus());
        snap.setCurrentPopulation(parsed.currentPopulation());
        snap.setVillageCount(parsed.villageCount());
        snap.setAllianceTravianId(parsed.allianceTravianId());
        snap.setAllianceName(parsed.allianceName());

        if (snap.isEmpty()) {
            // Same stamp-and-skip pattern as the no-html branch.
            player.recordScrape(player.getHeroLevel(), player.getHeroXp(), player.getHeroProductionBonus(),
                    player.getCurrentPopulation(), player.getVillageCount(),
                    player.getAllianceTravianId(), player.getAllianceName());
            enemyPlayerRepository.save(player);
            botEventService.record(player.getAccountId(), null, EVENT_ACTION,
                    BotEventOutcome.WARN,
                    "Enemy profile for uid " + player.getTravianUid() + " parsed to empty");
            return Optional.empty();
        }

        // Capture prior snapshot BEFORE we mutate, so the change detector
        // sees the real previous state (not the one we're about to write).
        EnemyProfileSnapshot prior = snapshotRepository
                .findFirstByEnemyPlayerIdOrderByCapturedAtDesc(player.getId())
                .orElse(null);
        String diff = changeDetector.describe(prior, snap);
        snap.setDiffSummary(diff);
        EnemyProfileSnapshot saved = snapshotRepository.save(snap);

        // Hero-level change is the signal we narrate to BotEvents. We
        // intentionally only fire on a level change (not on every diff) to
        // keep the activity log readable — XP and pop deltas land in the
        // detail timeline but don't bubble to the top.
        Integer priorHero = prior == null ? null : prior.getHeroLevel();
        Integer nextHero = snap.getHeroLevel();
        if (priorHero != null && nextHero != null && !Objects.equals(priorHero, nextHero)) {
            String direction = nextHero < priorHero ? "DIED/dropped" : "leveled up";
            botEventService.record(player.getAccountId(), null, EVENT_ACTION,
                    BotEventOutcome.OK,
                    "Enemy " + player.getPlayerName() + " hero " + direction
                            + ": " + priorHero + " -> " + nextHero);
        }

        // Update the summary row last — order matters: prior snapshot is the
        // pre-mutation truth.
        if (parsed.tribeId() != null && !Objects.equals(parsed.tribeId(), player.getTribeId())) {
            player.setTribeId(parsed.tribeId());
        }
        player.recordScrape(
                parsed.heroLevel(),
                parsed.heroXp(),
                parsed.heroProductionBonus(),
                parsed.currentPopulation(),
                parsed.villageCount(),
                parsed.allianceTravianId(),
                parsed.allianceName()
        );
        enemyPlayerRepository.save(player);

        return Optional.of(saved);
    }

    /**
     * Refresh up to {@code maxPerRun} stale enemies for this account, picking
     * the oldest-scraped first. Returns the count of enemies we actually
     * scraped (a parse miss still counts as "scraped" because we attempted).
     */
    public int refreshStaleForAccount(Long accountId, Duration staleness, int maxPerRun) {
        if (accountId == null || staleness == null || maxPerRun <= 0) return 0;
        Instant cutoff = Instant.now().minus(staleness);
        List<EnemyPlayer> stale = enemyPlayerRepository
                .findAllByAccountIdAndLastScrapedAtBeforeOrderByLastScrapedAtAsc(accountId, cutoff);

        int scraped = 0;
        for (EnemyPlayer p : stale) {
            if (scraped >= maxPerRun) break;
            try {
                scrape(p.getId());
            } catch (RuntimeException e) {
                // Defensive — one bad row must not abort the remaining N-1.
                log.warn("EnemyProfileScraper: scrape of enemy {} failed: {}",
                        p.getId(), e.getMessage());
            }
            scraped++;
        }
        return scraped;
    }

    /**
     * Best-effort profile fetch. Tries the standard {@code /spieler.php?uid=}
     * URL first (every T4.6 skin honours this), then the alternative
     * {@code /profile?uid=} that some heroV2-only skins use. Returns
     * {@code null} if both fail.
     */
    private String tryFetchProfile(BrowserTravianClient client, String serverUrl, Long uid) {
        String primary = serverUrl + "/spieler.php?uid=" + uid;
        try {
            String html = client.getWaitingFor(primary, ".playerDetails, .playerProfile, .details, #details",
                    PROFILE_WAIT_MS);
            if (html != null && !html.isBlank()) return html;
        } catch (RuntimeException e) {
            log.debug("Primary profile URL {} failed: {}", primary, e.getMessage());
        }
        String fallback = serverUrl + "/profile?uid=" + uid;
        try {
            String html = client.getWaitingFor(fallback, ".playerDetails, .playerProfile, .details, #details",
                    PROFILE_WAIT_MS);
            if (html != null && !html.isBlank()) return html;
        } catch (RuntimeException e) {
            log.debug("Fallback profile URL {} failed: {}", fallback, e.getMessage());
        }
        return null;
    }

    /**
     * Cleanup hook — wipe this account's enemy intel when the account is
     * deleted. {@code MANDATORY} so the cleanup stays inside the deletion
     * transaction. Snapshots are nuked first since they FK-conceptually
     * depend on {@code EnemyPlayer.id}.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        List<EnemyPlayer> rows = enemyPlayerRepository
                .findAllByAccountIdOrderByLastScrapedAtDesc(event.accountId());
        if (!rows.isEmpty()) {
            List<Long> ids = new ArrayList<>(rows.size());
            for (EnemyPlayer p : rows) ids.add(p.getId());
            snapshotRepository.deleteAllByEnemyPlayerIdIn(ids);
        }
        enemyPlayerRepository.deleteAllByAccountId(event.accountId());
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
