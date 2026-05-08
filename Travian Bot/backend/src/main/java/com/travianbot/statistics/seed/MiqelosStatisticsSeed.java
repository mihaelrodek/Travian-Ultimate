package com.travianbot.statistics.seed;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.statistics.domain.StatCategory;
import com.travianbot.statistics.domain.StatSnapshot;
import com.travianbot.statistics.domain.Top10Entry;
import com.travianbot.statistics.persistence.StatSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * One-shot mock data seeder for the {@code miqelos-ts31-x3-europe-travian-com}
 * account, so the rewritten Statistics dashboard has something interesting to
 * render before real captures land. Idempotent — checks for any existing
 * snapshot on the account and bails if one is already there, so manual
 * "Capture now" runs (or the scheduler) won't get blown away on the next
 * restart.
 *
 * <h3>Targeting</h3>
 * Looks for an account whose serverUrl host (case-insensitive,
 * normalised to letters / digits / dots) matches
 * {@code ts31.x3.europe.travian.com}. We don't pin to the nickname because
 * the user might rename the account, and the server is what makes this
 * account unique.
 *
 * <h3>Shape of the seeded data</h3>
 *   - 72 hours of history, one snapshot per category per hour (= 72 × 4 = 288
 *     {@link StatSnapshot} rows).
 *   - Each snapshot carries 10 {@link Top10Entry} rows — same fixed roster of
 *     player names per category so the chart curves stay continuous.
 *   - Scores trend upward over time with deterministic per-player jitter so
 *     the chart looks alive but stable across reloads (seeded
 *     {@link Random} keyed on {@code (category, playerName)}).
 *   - Ranks recompute every snapshot from the score order, so movement
 *     between adjacent captures shows up as the {@code rankDelta} the UI
 *     plots.
 */
@Component
public class MiqelosStatisticsSeed {

    private static final Logger log = LoggerFactory.getLogger(MiqelosStatisticsSeed.class);

    /**
     * Server host this seed targets. Case-insensitive substring match — the
     * stored serverUrl might carry a scheme + path, e.g.
     * {@code https://ts31.x3.europe.travian.com/}, and we want a hit either
     * way.
     */
    private static final String TARGET_HOST = "ts31.x3.europe.travian.com";

    /** How far back the synthetic history reaches. */
    private static final int HISTORY_HOURS = 72;

    /**
     * Roster of plausible Travian player names per category. Same fixed list
     * keeps the curves continuous over time so the chart doesn't look like
     * random noise. Names borrow from the user's own server context (Greek /
     * EU-speed-3) so the dashboard reads as familiar.
     */
    private static final String[] PVP_PLAYERS = {
            "Achilleas", "Spartacus", "Leonidas", "Thanatos", "Kratos",
            "Hektor", "Perseus", "Aias", "Diomedes", "Patroclus"
    };
    private static final String[] PVE_PLAYERS = {
            "FarmKing", "NatarSlayer", "BeastHunter", "WildOne", "Ravager",
            "Clearcut", "OasisLord", "GreenReaper", "BushBoss", "WoodsmanX"
    };
    private static final String[] DEFENSE_PLAYERS = {
            "Aigis", "WallStanding", "Bulwark", "TurtleShell", "PhalanxOne",
            "Hoplite", "IronGate", "TheBrick", "DefMaster", "ShieldBro"
    };
    private static final String[] RAIDER_PLAYERS = {
            "GoldGrabber", "QuickFingers", "BootyHunter", "Magpie", "RaidNight",
            "GhostRider", "FastFists", "ThunderClap", "SilverThief", "HoardKing"
    };

    private final AccountRepository accountRepository;
    private final StatSnapshotRepository snapshotRepository;

    public MiqelosStatisticsSeed(AccountRepository accountRepository,
                                 StatSnapshotRepository snapshotRepository) {
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedIfNeeded() {
        Account target = findTargetAccount();
        if (target == null) {
            log.debug("Mock stats seed: no account on {} — skipping", TARGET_HOST);
            return;
        }
        // Idempotency: any existing snapshot for this account means real captures
        // (or a previous seed run) already exist. Don't clobber them.
        boolean hasAny = snapshotRepository
                .findFirstByAccountIdAndCategoryOrderByCapturedAtDesc(target.getId(), StatCategory.PVP)
                .isPresent()
                || snapshotRepository
                        .findFirstByAccountIdAndCategoryOrderByCapturedAtDesc(target.getId(), StatCategory.PVE)
                        .isPresent()
                || snapshotRepository
                        .findFirstByAccountIdAndCategoryOrderByCapturedAtDesc(target.getId(), StatCategory.DEFENSE)
                        .isPresent()
                || snapshotRepository
                        .findFirstByAccountIdAndCategoryOrderByCapturedAtDesc(target.getId(), StatCategory.RAIDERS)
                        .isPresent();
        if (hasAny) {
            log.debug("Mock stats seed: account {} already has snapshots — skipping", target.getId());
            return;
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        List<StatSnapshot> toSave = new ArrayList<>();
        for (int hourBack = HISTORY_HOURS; hourBack >= 0; hourBack--) {
            Instant ts = now.minus(hourBack, ChronoUnit.HOURS);
            int t = HISTORY_HOURS - hourBack; // 0..HISTORY_HOURS (so values trend up)
            toSave.add(buildSnapshot(target, StatCategory.PVP, ts, t, PVP_PLAYERS, 1_500, 80));
            toSave.add(buildSnapshot(target, StatCategory.PVE, ts, t, PVE_PLAYERS, 900, 45));
            toSave.add(buildSnapshot(target, StatCategory.DEFENSE, ts, t, DEFENSE_PLAYERS, 2_200, 110));
            toSave.add(buildSnapshot(target, StatCategory.RAIDERS, ts, t, RAIDER_PLAYERS, 95_000, 4_800));
        }
        snapshotRepository.saveAll(toSave);
        log.info("Mock stats seed: wrote {} snapshots ({} hours × 4 categories) for account {} ({})",
                toSave.size(), HISTORY_HOURS + 1, target.getId(), TARGET_HOST);
    }

    /**
     * Walk every account looking for a serverUrl that matches the target
     * host. Cheap on the user's typical N≈10 accounts — no need for a
     * dedicated repo query just for the seed bean.
     */
    private Account findTargetAccount() {
        String needle = TARGET_HOST.toLowerCase();
        for (Account a : accountRepository.findAll()) {
            String url = a.getServerUrl();
            if (url == null) continue;
            if (url.toLowerCase().contains(needle)) return a;
        }
        return null;
    }

    /**
     * Build one snapshot for a category. Seeds a deterministic RNG per
     * (category, player) so the same player name on the same category
     * always ranks similarly across the run. The {@code t} parameter
     * provides the upward trend — multiplied into a base score and
     * jittered.
     */
    private StatSnapshot buildSnapshot(Account account, StatCategory category, Instant ts, int t,
                                       String[] roster, long baseScore, long perTickGain) {
        StatSnapshot snap = new StatSnapshot(account, account.getServerUrl(), ts, category);

        // Compute (player, score) tuples first so we can rank them after.
        List<long[]> scored = new ArrayList<>(roster.length);
        for (int i = 0; i < roster.length; i++) {
            String name = roster[i];
            long seed = ((long) category.ordinal() * 1_000_003L) + name.hashCode();
            Random rng = new Random(seed);
            // Per-player baseline rank weight (1..0.55) so the leaderboard isn't a
            // straight line from index 0 down — strong players still pull ahead.
            double weight = 1.0 - (i * 0.05);
            long base = (long) (baseScore * weight);
            // Trend + a small per-tick wobble keyed off the rng so successive
            // snapshots stay close instead of jumping randomly.
            long trend = perTickGain * t;
            long wobble = rng.nextInt(Math.max(1, (int) (perTickGain / 4))) - (perTickGain / 8);
            long score = Math.max(0L, base + trend + wobble);
            scored.add(new long[] { i, score });
        }
        // Re-rank by score descending so the rendered table reads like the
        // real Travian top-10 (best first).
        scored.sort(Comparator.comparingLong((long[] row) -> row[1]).reversed());
        int rank = 1;
        for (long[] row : scored) {
            String name = roster[(int) row[0]];
            snap.addEntry(new Top10Entry(snap, rank++, name, row[1]));
        }
        return snap;
    }
}
