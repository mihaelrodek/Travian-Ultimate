package com.travianbot.incoming.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.incoming.analyzer.AnalysisResult;
import com.travianbot.incoming.analyzer.TournamentSquareAnalyzer;
import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.persistence.IncomingAttackRepository;
import com.travianbot.incoming.scraper.RallyPointIncomingParser;
import com.travianbot.incoming.scraper.RallyPointIncomingParser.ParsedIncomingAttack;
import com.travianbot.notification.NotificationDispatcher;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.stream.SseEventBroadcaster;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core read/write surface for the {@link IncomingAttack} feature.
 * <p>
 * Two write paths:
 * <ul>
 *   <li>{@link #scrapeForAccount(Long)} — pulls the rally-point page through
 *       the account's already-open browser session, parses it, and upserts
 *       new waves. Idempotent — safe to call every 90s from the scheduler.</li>
 *   <li>{@link #acknowledge(Long)} — flips the read flag so a row disappears
 *       from the "needs attention" view.</li>
 * </ul>
 * Reads go through {@link #listFor(Long)} — a simple proxy that keeps the
 * read tx open while the entities are loaded.
 */
@Service
@Transactional
public class IncomingAttackService {

    private static final Logger log = LoggerFactory.getLogger(IncomingAttackService.class);

    private static final String EVENT_ACTION = "SCRAPE_INCOMING";

    /**
     * Max length of the {@code analysis_json} column — keep in sync with
     * {@link IncomingAttack#getAnalysisJson()}. Oversized payloads get
     * truncated rather than failing the row save.
     */
    private static final int ANALYSIS_JSON_MAX = 2000;

    private final IncomingAttackRepository repository;
    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final VillageRepository villageRepository;
    /**
     * The original speed-bucket classifier — still injected for backwards
     * compatibility (other modules may call it through this bean), but the
     * inverse {@link TournamentSquareAnalyzer} below is what now drives the
     * "Likely unit / TS" column. We keep both: the bucket is a robust fallback
     * when the inverse solver finds nothing within tolerance.
     */
    private final AttackAnalyzer analyzer;
    private final TournamentSquareAnalyzer tsAnalyzer;
    private final ServerRepository serverRepository;
    private final ObjectMapper objectMapper;
    private final BotEventService botEventService;
    private final NotificationDispatcher notifiers;
    private final ReturnCatchPlanner returnCatchPlanner;
    private final SseEventBroadcaster broadcaster;

    public IncomingAttackService(
            IncomingAttackRepository repository,
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            VillageRepository villageRepository,
            AttackAnalyzer analyzer,
            TournamentSquareAnalyzer tsAnalyzer,
            ServerRepository serverRepository,
            ObjectMapper objectMapper,
            BotEventService botEventService,
            NotificationDispatcher notifiers,
            ReturnCatchPlanner returnCatchPlanner,
            SseEventBroadcaster broadcaster
    ) {
        this.repository = repository;
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.villageRepository = villageRepository;
        this.analyzer = analyzer;
        this.tsAnalyzer = tsAnalyzer;
        this.serverRepository = serverRepository;
        this.objectMapper = objectMapper;
        this.botEventService = botEventService;
        this.notifiers = notifiers;
        this.returnCatchPlanner = returnCatchPlanner;
        this.broadcaster = broadcaster;
    }

    /**
     * Scrapes the rally-point incoming-attacks page for the given account and
     * upserts every wave we find. Returns the count of newly-stored rows
     * (deduped against the {@code (accountId, targetVillageId, arrivalAt,
     * sourceX, sourceY)} unique key).
     * <p>
     * <strong>Determining the target village:</strong> the rally-point page
     * groups incoming waves under the village currently active in the
     * browser. We use that village (looked up by the account's first village
     * with the active coords if we can match them, else the first village
     * the account owns) as the {@code targetVillageId}. A future improvement
     * would parse the village name out of the page header — punted for now
     * because the current account context is sufficient for the planner's
     * needs.
     *
     * @throws IllegalStateException when no logged-in browser session exists
     */
    public int scrapeForAccount(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new IllegalStateException("not logged in for account " + accountId);
        }

        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            throw new IllegalStateException("account not found: " + accountId);
        }

        List<Village> villages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        if (villages.isEmpty()) {
            log.debug("Skipping incoming scrape for account {} — no villages yet", accountId);
            return 0;
        }

        // We don't know from the HTML alone which village the attack lands
        // on (Travian renders the rally-point in the context of whatever
        // village is active in the browser). We assume the active village is
        // the first one for now — see the javadoc; the planner doesn't lean
        // on this assumption because it consumes attacker coords, not the
        // defender's village pick.
        Village targetVillage = villages.get(0);

        Instant now = Instant.now();
        String url = account.getServerUrl() + "/build.php?gid=16&tt=1";
        String html;
        try {
            html = client.get(url);
        } catch (RuntimeException e) {
            botEventService.record(accountId, targetVillage.getId(), EVENT_ACTION,
                    BotEventOutcome.ERROR,
                    "Rally-point page fetch failed: " + e.getMessage());
            throw e;
        }

        // Anchor detectedAt to the page's "Server time:" header when present
        // — keeps it on the same clock as arrival, even if the bot's wall
        // clock disagrees with the game server by a non-trivial offset.
        Instant detectedAt = RallyPointIncomingParser.resolveServerNow(html, now);
        List<ParsedIncomingAttack> parsed = RallyPointIncomingParser.parse(html, now);

        int newCount = 0;
        for (ParsedIncomingAttack p : parsed) {
            if (p.arrivalAt() == null) continue;

            Optional<IncomingAttack> existing = repository
                    .findFirstByAccountIdAndTargetVillageIdAndArrivalAtAndSourceXAndSourceY(
                            accountId,
                            targetVillage.getId(),
                            p.arrivalAt(),
                            p.sourceX(),
                            p.sourceY()
                    );
            if (existing.isPresent()) {
                // Re-scrape of an already-known wave. Nothing to do — we
                // deliberately don't update detectedAt because the original
                // detection time is the one that gives the cleanest speed
                // estimate.
                continue;
            }

            IncomingAttack row = new IncomingAttack(accountId, targetVillage.getId(),
                    p.arrivalAt(), detectedAt);
            row.setSourceX(p.sourceX());
            row.setSourceY(p.sourceY());
            row.setSourceVillageName(p.sourceVillageName());
            row.setAttackerName(p.attackerName());
            row.setAttackerAlliance(p.attackerAlliance());
            row.setWaveCount(p.waveCount());
            row.setIsAttack(p.isAttack());
            row.recompute(targetVillage.getCoordinateX(), targetVillage.getCoordinateY());
            applyTsAnalysis(row, account);
            IncomingAttack saved = repository.save(row);
            // Fire-and-forget Discord notify (async; no-op when disabled or
            // webhook unset). Outside the row's persistence path so a
            // network blip can't roll back the import.
            notifiers.notifyIncomingAttack(saved);
            // Live push so the sidebar badge + attack notifications react at once
            // instead of on the next 30s poll.
            broadcaster.broadcastAfterCommit("incoming-attack",
                    Map.of("accountId", saved.getAccountId(), "attackId", saved.getId()));
            maybeNotifyReturnCatch(saved);
            newCount++;
        }

        if (newCount > 0) {
            botEventService.record(accountId, targetVillage.getId(), EVENT_ACTION,
                    BotEventOutcome.WARN,
                    newCount + " new incoming attack(s) detected");
        } else {
            botEventService.record(accountId, targetVillage.getId(), EVENT_ACTION,
                    BotEventOutcome.OK,
                    "No new incoming attacks");
        }
        return newCount;
    }

    /**
     * Manual paste-in import — accepts text the user copied from their rally
     * point overview (Ctrl+A → Ctrl+C → Ctrl+V into the textbox on the
     * Defense → Report incoming attacks page). Parses either HTML or plain
     * text via {@link RallyPointIncomingParser#parsePastedContent}, then runs
     * the same upsert + dedup path as the scheduled scrape.
     * <p>
     * Falls back gracefully on no-content: returns 0 instead of throwing so
     * the UI can show "nothing recognised" without an error banner.
     */
    public int importPastedText(Long accountId, String body) {
        if (body == null || body.isBlank()) return 0;
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) throw new IllegalStateException("account not found: " + accountId);

        List<Village> villages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        if (villages.isEmpty()) {
            log.debug("Paste-in import skipped for account {} — no villages yet", accountId);
            return 0;
        }
        Village defaultTarget = villages.get(0);
        Instant callerNow = Instant.now();
        // Anchor BOTH detectedAt and arrivalAt to the page's render time —
        // the parser uses it for arrival, and travel time = arrival -
        // detectedAt only stays correct if we feed both ends from the same
        // clock. Falls back to callerNow when the header is absent.
        Instant detectedAt = RallyPointIncomingParser.resolveServerNow(body, callerNow);

        List<ParsedIncomingAttack> parsed = RallyPointIncomingParser.parsePastedContent(body, callerNow);

        int newCount = 0;
        for (ParsedIncomingAttack p : parsed) {
            if (p.arrivalAt() == null) continue;

            // Prefer the village whose coords match the in-parens defender
            // pair from the paste — otherwise the distance recompute uses
            // the wrong endpoint and the analyzer chases the wrong f/h.
            Village targetVillage = defaultTarget;
            if (p.defenderX() != null && p.defenderY() != null) {
                Optional<Village> matched = villageRepository
                        .findFirstByAccountIdAndCoordinateXAndCoordinateY(
                                accountId, p.defenderX(), p.defenderY());
                if (matched.isPresent()) {
                    targetVillage = matched.get();
                } else {
                    log.debug("Paste-in: no village at defender coords ({}|{}) on account {} — "
                                    + "falling back to default target {}",
                            p.defenderX(), p.defenderY(), accountId, defaultTarget.getId());
                }
            }

            Optional<IncomingAttack> existing = repository
                    .findFirstByAccountIdAndTargetVillageIdAndArrivalAtAndSourceXAndSourceY(
                            accountId, targetVillage.getId(),
                            p.arrivalAt(), p.sourceX(), p.sourceY());
            if (existing.isPresent()) continue;

            IncomingAttack row = new IncomingAttack(accountId, targetVillage.getId(),
                    p.arrivalAt(), detectedAt);
            row.setSourceX(p.sourceX());
            row.setSourceY(p.sourceY());
            row.setSourceVillageName(p.sourceVillageName());
            row.setAttackerName(p.attackerName());
            row.setAttackerAlliance(p.attackerAlliance());
            row.setWaveCount(p.waveCount());
            row.setIsAttack(p.isAttack());
            // Use the PARSED defender coords for distance — they came
            // straight from the paste and are the ground truth, regardless
            // of which Village row we pick for the FK. Falling back to
            // targetVillage.coords would make the row's distance trail the
            // wrong end of the trip when the FK lookup picked
            // {@code defaultTarget} (no matching village in DB).
            int recX = p.defenderX() != null ? p.defenderX() : targetVillage.getCoordinateX();
            int recY = p.defenderY() != null ? p.defenderY() : targetVillage.getCoordinateY();
            row.recompute(recX, recY);
            applyTsAnalysis(row, account);
            IncomingAttack saved = repository.save(row);
            // Fire-and-forget Discord notify (async; no-op when disabled or
            // webhook unset). Outside the row's persistence path so a
            // network blip can't roll back the import.
            notifiers.notifyIncomingAttack(saved);
            // Live push so the sidebar badge + attack notifications react at once
            // instead of on the next 30s poll.
            broadcaster.broadcastAfterCommit("incoming-attack",
                    Map.of("accountId", saved.getAccountId(), "attackId", saved.getId()));
            maybeNotifyReturnCatch(saved);
            newCount++;
        }
        botEventService.record(accountId, defaultTarget.getId(), EVENT_ACTION,
                newCount > 0 ? BotEventOutcome.WARN : BotEventOutcome.OK,
                "Paste-in import: " + newCount + " new wave(s), " + parsed.size() + " parsed");
        return newCount;
    }

    /**
     * Run the Tournament-Square inverse solver against {@code row} and stamp
     * its output back onto the entity. Tolerant — any exception or null
     * intermediate result leaves the analysis fields null, the row is still
     * persisted by the caller. No throws.
     * <p>
     * The server-side speed multiplier + map radius are looked up from the
     * canonical {@link Server} row matching the account's server URL.
     * Legacy rows (server with no speed parsed) get the defaults of
     * {@code speed=1, radius=200} plus a warn log.
     */
    void applyTsAnalysis(IncomingAttack row, Account account) {
        try {
            if (row.getDistanceFields() == null || row.getDistanceFields() <= 0.0) return;
            if (row.getArrivalAt() == null || row.getDetectedAt() == null) return;

            long secs = Duration.between(row.getDetectedAt(), row.getArrivalAt()).getSeconds();
            if (secs <= 0L) return;
            Duration travel = Duration.ofSeconds(secs);

            int speed = 1;
            int radius = 200;
            if (account != null && account.getServerUrl() != null) {
                String canonical = Server.canonicalUrl(account.getServerUrl());
                Optional<Server> serverRow = serverRepository.findByUrl(canonical);
                if (serverRow.isPresent()) {
                    Server s = serverRow.get();
                    if (s.getSpeedMultiplier() != null) {
                        speed = s.getSpeedMultiplier();
                    } else {
                        log.warn("Server row id={} url={} has null speedMultiplier — defaulting to 1",
                                s.getId(), s.getUrl());
                    }
                    if (s.getMapRadius() != null) radius = s.getMapRadius();
                } else {
                    log.warn("No Server row matches account {} url '{}' — defaulting speed=1 radius=200",
                            account.getId(), account.getServerUrl());
                }
            }

            AnalysisResult result = tsAnalyzer.analyse(
                    row.getDistanceFields(), travel, speed, radius,
                    row.getManualArtifactMultiplier());
            if (result == null) {
                row.setAnalysis(null, null, null, null);
                return;
            }
            String unitName = null;
            Integer tsLevel = null;
            if (result.candidates() != null && !result.candidates().isEmpty()) {
                AnalysisResult.Candidate top = result.candidates().get(0);
                unitName = top.unitName();
                tsLevel = top.tsLevel();
            }
            String json;
            try {
                json = objectMapper.writeValueAsString(result);
                if (json != null && json.length() > ANALYSIS_JSON_MAX) {
                    log.debug("TS analysis JSON {} chars — exceeded column cap, dropping payload", json.length());
                    json = null;
                }
            } catch (JsonProcessingException e) {
                log.debug("TS analysis JSON serialise failed: {}", e.getMessage());
                json = null;
            }
            row.setAnalysis(unitName, tsLevel, result.observedFieldsPerHour(), json);
        } catch (RuntimeException e) {
            log.warn("TS analysis failed for row (account={} targetVillage={}): {}",
                    row.getAccountId(), row.getTargetVillageId(), e.toString());
            // Leave analysis fields untouched / null; caller still persists row.
        }
    }

    /**
     * Re-run the Tournament-Square solver against every {@link IncomingAttack}
     * the account has accumulated and update the analysis columns in place.
     * Useful when the user (a) just adds the {@code Server.speedMultiplier}
     * value, or (b) the catalog changes and old rows need to be re-scored
     * without rescraping. Returns the count of rows touched.
     * <p>
     * Skips rows with no distance / no travel-time signal — those weren't
     * analysable to begin with and re-running won't help.
     */
    public int reanalyseForAccount(Long accountId) {
        final Long aid = accountId;
        Account account = accountRepository.findById(aid).orElse(null);
        if (account == null) throw new IllegalStateException("account not found: " + aid);
        List<IncomingAttack> rows = repository.findAllByAccountIdOrderByArrivalAtAsc(aid);
        int touched = 0;
        for (IncomingAttack row : rows) {
            if (row.getDistanceFields() == null) continue;
            if (row.getArrivalAt() == null || row.getDetectedAt() == null) continue;
            applyTsAnalysis(row, account);
            repository.save(row);
            touched++;
        }
        log.info("Reanalysed {} incoming attack row(s) for account {}", touched, aid);
        return touched;
    }

    /**
     * Stamp a manual speed-artifact multiplier on the row and re-run the
     * Tournament-Square solver immediately so the row's "likely unit / TS"
     * columns reflect the new assumption.
     * <p>
     * Pass {@code multiplier = null} to clear the override — the analyzer
     * then ignores artifacts (treats as 1.0) on its next run. Caller-supplied
     * values that don't match the seven legal in-game multipliers
     * (0.33 / 0.5 / 0.67 / 1.0 / 1.5 / 2.0 / 3.0) are silently coerced to
     * 1.0 by the analyzer's {@code normaliseArtifact} guard.
     */
    public IncomingAttack setManualArtifactMultiplier(Long attackId, Double multiplier) {
        IncomingAttack row = repository.findById(attackId)
                .orElseThrow(() -> new IllegalArgumentException("incoming attack not found: " + attackId));
        row.setManualArtifactMultiplier(multiplier);
        Account account = accountRepository.findById(row.getAccountId()).orElse(null);
        applyTsAnalysis(row, account);
        return repository.save(row);
    }

    /** Every recorded attack for the account, soonest arrival first. */
    @Transactional(readOnly = true)
    public List<IncomingAttack> listFor(Long accountId) {
        return repository.findAllByAccountIdOrderByArrivalAtAsc(accountId);
    }

    /**
     * Drop every incoming-attack row for the account. Used as a hard reset
     * after a parser fix lands and previously-stored rows still have garbage
     * source coords / distances. Returns the number of rows removed.
     */
    public int clearAllFor(Long accountId) {
        List<IncomingAttack> rows = repository.findAllByAccountIdOrderByArrivalAtAsc(accountId);
        int n = rows.size();
        if (n > 0) {
            repository.deleteAllByAccountId(accountId);
            log.info("Cleared {} incoming-attack row(s) for account {}", n, accountId);
        }
        return n;
    }

    /**
     * Flip the row's {@code acknowledged} flag to {@code true}. Idempotent —
     * acking an already-acked row is a no-op write.
     */
    public IncomingAttack acknowledge(Long id) {
        IncomingAttack row = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("incoming attack not found: " + id));
        if (Boolean.TRUE.equals(row.getAcknowledged())) return row;
        row.setAcknowledged(true);
        return repository.save(row);
    }

    /**
     * Cleanup hook — wipe this account's incoming history when the account
     * itself is deleted. {@code MANDATORY} so the row removal stays inside
     * the deletion transaction.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }

    /**
     * Ask the planner for the soonest viable return-catch launch against the
     * just-saved attack and fire a Discord notification when one is found.
     * Best-effort — any planner/notifier error is logged and swallowed so a
     * failure here can't undo the import.
     */
    private void maybeNotifyReturnCatch(IncomingAttack saved) {
        try {
            returnCatchPlanner.suggestBestForAttack(saved)
                    .ifPresent(notifiers::notifyReturnCatch);
        } catch (RuntimeException e) {
            log.debug("Return-catch notify failed for attack {}: {}", saved.getId(), e.toString());
        }
    }
}
