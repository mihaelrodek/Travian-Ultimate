package com.travianbot.incoming.analyzer;

import com.travianbot.incoming.analyzer.TroopSpeedCatalog.TroopSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Inverse-problem solver: given the observed arrival speed of an incoming
 * wave, enumerate every plausible {@code (unit, Tournament-Square level,
 * boots)} combination that reproduces that speed within tolerance.
 * <p>
 * <strong>Forward model (T4.6 Legends).</strong>
 * <pre>
 *   distance         = sqrt(dx^2 + dy^2)
 *   base_speed       = unit_base_fields_per_hour * server_speed_multiplier
 *   boots_factor     = 1 + boots_bonus              (0 / 0.25 / 0.5 / 0.75)
 *   artifact_factor  = artifact_multiplier          (1.0 default — see below)
 *   hero_factor      = boots_factor * artifact_factor
 *   ts_factor_excess = 1 + ts_level * 0.20          (applies BEYOND first 20 fields)
 *
 *   if distance &lt;= 20:
 *       effective_speed = base_speed * hero_factor
 *       time            = distance / effective_speed
 *   else:
 *       fast_speed      = base_speed * hero_factor
 *       excess_speed    = base_speed * hero_factor * ts_factor_excess
 *       time            = 20 / fast_speed + (distance - 20) / excess_speed
 * </pre>
 * <p>
 * <strong>Artifact handling.</strong> Speed artifacts in T4.6 are <em>direct
 * multipliers</em>, not additive bonuses: the in-game picker only offers
 * {0.33x, 0.5x, 0.67x, 1.0x (none), 1.5x, 2.0x, 3.0x}. The solver does NOT
 * brute-force the artifact dimension — the search space is huge and the
 * solver has no way to ground a guess. By default we assume <strong>no
 * artifact (multiplier 1.0)</strong>. The caller may pass an explicit
 * {@code artifactMultiplier} (one of the seven legal values) when the user
 * manually selects one in the UI, and we then run the solver with that single
 * fixed multiplier. See {@link #ALLOWED_ARTIFACT_MULTIPLIERS}.
 * <p>
 * <strong>Search.</strong> Roughly {@code 6 tribes * 8 attackable units *
 * 21 TS levels * 4 boots ≈ 4k} combos per attack. Sync brute-force is fast
 * enough — no need to short-circuit.
 * <p>
 * <strong>Ranking.</strong> Candidates are sorted by:
 * <ol>
 *   <li>Closest match (lower {@code deviation} wins)</li>
 *   <li>Simpler explanation (fewer non-zero modifiers wins — Occam's razor)</li>
 *   <li>Faster base unit (player-intent heuristic)</li>
 * </ol>
 * Only the top 3 survive.
 * <p>
 * <strong>Edge cases.</strong>
 * <ul>
 *   <li>{@code distance &lt;= 20} → TS doesn't matter; we still iterate but
 *       collapse to {@code tsLevel=0} when reporting so the breakdown isn't
 *       littered with duplicate same-time rows.</li>
 *   <li>{@code distance == 0} or {@code travelTime &lt;= 0} → returns empty.</li>
 *   <li>No matches within 2% → widens to 5%; if still empty → empty list
 *       with summary {@code "no match"}.</li>
 * </ul>
 * <p>
 * <strong>Hero assumption.</strong> We can't tell from the rally-point
 * incoming view whether a hero rode with the army. The solver assumes hero
 * IS present whenever boots modifiers are non-zero. If the attacker actually
 * sent a hero-less army, the solver may suggest a slower unit (the extra
 * speed has to come from somewhere → boots account for it). This is
 * documented as a known limitation, not a bug.
 */
@Service
public class TournamentSquareAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TournamentSquareAnalyzer.class);

    /** Tolerance bands — we try the tight one first, widen on no match. */
    private static final double TIGHT_TOLERANCE = 0.02;   // 2%
    private static final double LOOSE_TOLERANCE = 0.05;   // 5%

    /** Max TS level the building can reach in T4.6. */
    private static final int MAX_TS_LEVEL = 20;

    /** Boots bonuses: none, regular, mercenary, warrior boots. */
    private static final double[] BOOTS_BONUSES = {0.0, 0.25, 0.5, 0.75};

    /**
     * Legal speed-artifact multipliers (exact set offered by the in-game
     * picker). 1.0 = no artifact / unequipped. Slowdown artifacts (the three
     * sub-1 values) are normally found on the <em>defender's</em> village
     * and slow incoming troops; speedup (the three super-1 values) sit on the
     * attacker. The analyzer uses whichever value the user manually picks —
     * it doesn't try to guess which side equipped it.
     */
    public static final double[] ALLOWED_ARTIFACT_MULTIPLIERS = {
            0.33, 0.5, 0.67, 1.0, 1.5, 2.0, 3.0
    };

    /** Tolerance for matching a user-supplied multiplier to the allowed set. */
    private static final double ARTIFACT_EPSILON = 0.01;

    /** Number of candidates retained in the final result. */
    private static final int TOP_K = 3;

    /**
     * Convenience overload — runs the solver assuming no speed artifact
     * (multiplier 1.0). Equivalent to {@link #analyse(double, Duration, int, int, Double)}
     * with {@code artifactMultiplier = null}.
     */
    public AnalysisResult analyse(double distanceFields, Duration travelTime,
                                  int serverSpeedMultiplier, int mapRadius) {
        return analyse(distanceFields, travelTime, serverSpeedMultiplier, mapRadius, null);
    }

    /**
     * Run the inverse search.
     *
     * @param distanceFields        observed distance from attacker source to defender, in fields
     * @param travelTime            observed travel time (arrival - detection)
     * @param serverSpeedMultiplier server speed (1, 2, 3, 5, 10); 1 if unknown
     * @param mapRadius             map half-width (200 normally, 100 for x10); not yet used in the
     *                              math but accepted so the API is forward-compatible with a future
     *                              wrap-around distance fix
     * @param artifactMultiplier    optional manual artifact multiplier; must match one of
     *                              {@link #ALLOWED_ARTIFACT_MULTIPLIERS} within
     *                              {@code ARTIFACT_EPSILON}; {@code null} or an unrecognised
     *                              value collapses to 1.0 (no artifact). The solver does NOT
     *                              iterate over artifacts — passing nothing means the
     *                              analysis ignores artifacts entirely.
     * @return an {@link AnalysisResult} with up to 3 ranked candidates plus a one-line summary
     */
    public AnalysisResult analyse(double distanceFields, Duration travelTime,
                                  int serverSpeedMultiplier, int mapRadius,
                                  Double artifactMultiplier) {
        if (distanceFields <= 0.0) {
            return new AnalysisResult(0.0, List.of(), "no match");
        }
        if (travelTime == null) {
            return new AnalysisResult(0.0, List.of(), "no match");
        }
        long secs = travelTime.getSeconds();
        if (secs <= 0L) {
            return new AnalysisResult(0.0, List.of(), "no match");
        }
        int speedMultiplier = serverSpeedMultiplier <= 0 ? 1 : serverSpeedMultiplier;
        if (mapRadius <= 0) {
            log.debug("analyse() called with mapRadius={} — treating as 200", mapRadius);
        }

        double artifact = normaliseArtifact(artifactMultiplier);

        double observedSeconds = secs;
        double observedHours = observedSeconds / 3600.0;
        double observedSpeed = distanceFields / observedHours;

        // Pass 1 — tight tolerance.
        List<AnalysisResult.Candidate> matches = search(distanceFields, observedSeconds,
                speedMultiplier, artifact, TIGHT_TOLERANCE);

        // Pass 2 — widen if nothing landed.
        if (matches.isEmpty()) {
            matches = search(distanceFields, observedSeconds, speedMultiplier, artifact, LOOSE_TOLERANCE);
        }

        if (matches.isEmpty()) {
            return new AnalysisResult(observedSpeed, List.of(), "no match");
        }

        // Rank: deviation asc → modifierCount asc → baseSpeed desc
        matches.sort(Comparator
                .comparingDouble(AnalysisResult.Candidate::deviation)
                .thenComparingInt(AnalysisResult.Candidate::modifierCount)
                .thenComparing(Comparator.comparingInt(this::baseSpeedOf).reversed()));

        List<AnalysisResult.Candidate> top = matches.size() <= TOP_K
                ? matches
                : new ArrayList<>(matches.subList(0, TOP_K));

        return new AnalysisResult(observedSpeed, top, summarise(top, distanceFields));
    }

    /**
     * Coerce a caller-supplied multiplier to one of the seven legal values.
     * Null, NaN, or anything outside the allowed set (beyond
     * {@link #ARTIFACT_EPSILON}) collapses to 1.0 with a debug log — silent
     * fall-through is intentional: a stale UI value shouldn't crash analysis.
     */
    private double normaliseArtifact(Double raw) {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return 1.0;
        for (double allowed : ALLOWED_ARTIFACT_MULTIPLIERS) {
            if (Math.abs(raw - allowed) < ARTIFACT_EPSILON) return allowed;
        }
        log.debug("Ignoring unknown artifactMultiplier={} — falling back to 1.0", raw);
        return 1.0;
    }

    private List<AnalysisResult.Candidate> search(double distanceFields, double observedSeconds,
                                                  int speedMultiplier, double artifactMultiplier,
                                                  double tolerance) {
        boolean shortHop = distanceFields <= 20.0;
        List<AnalysisResult.Candidate> matches = new ArrayList<>();

        for (TroopSpec spec : TroopSpeedCatalog.attackable()) {
            double baseSpeed = (double) spec.baseFieldsPerHour() * (double) speedMultiplier;
            if (baseSpeed <= 0.0) continue;

            for (double boots : BOOTS_BONUSES) {
                double bootsFactor = 1.0 + boots;
                double heroFactor = bootsFactor * artifactMultiplier;

                if (shortHop) {
                    double effectiveSpeed = baseSpeed * heroFactor;
                    double theoreticalHours = distanceFields / effectiveSpeed;
                    double theoreticalSeconds = theoreticalHours * 3600.0;
                    double deviation = Math.abs(theoreticalSeconds - observedSeconds) / observedSeconds;
                    if (deviation <= tolerance) {
                        matches.add(toCandidate(spec, 0, boots, artifactMultiplier,
                                theoreticalSeconds, deviation));
                    }
                } else {
                    for (int ts = 0; ts <= MAX_TS_LEVEL; ts++) {
                        double tsFactorExcess = 1.0 + ts * 0.20;
                        double fastSpeed = baseSpeed * heroFactor;
                        double excessSpeed = baseSpeed * heroFactor * tsFactorExcess;
                        double theoreticalHours =
                                20.0 / fastSpeed
                                        + (distanceFields - 20.0) / excessSpeed;
                        double theoreticalSeconds = theoreticalHours * 3600.0;
                        double deviation = Math.abs(theoreticalSeconds - observedSeconds) / observedSeconds;
                        if (deviation <= tolerance) {
                            matches.add(toCandidate(spec, ts, boots, artifactMultiplier,
                                    theoreticalSeconds, deviation));
                        }
                    }
                }
            }
        }
        return matches;
    }

    private AnalysisResult.Candidate toCandidate(TroopSpec spec, int tsLevel,
                                                 double boots, double artifactMultiplier,
                                                 double theoreticalSeconds, double deviation) {
        // "Did the user pick a non-default artifact?" — anything other than 1.0
        // counts as a modifier for Occam's-razor ranking.
        boolean artifactSet = Math.abs(artifactMultiplier - 1.0) > ARTIFACT_EPSILON;
        int modifierCount = (boots > 0.0 ? 1 : 0)
                + (artifactSet ? 1 : 0)
                + (tsLevel > 0 ? 1 : 0);
        return new AnalysisResult.Candidate(
                spec.tribeId(),
                spec.tIndex(),
                spec.name(),
                spec.role(),
                tsLevel,
                boots,
                artifactMultiplier,
                theoreticalSeconds,
                deviation,
                modifierCount
        );
    }

    private int baseSpeedOf(AnalysisResult.Candidate c) {
        TroopSpec spec = TroopSpeedCatalog.get(c.tribeId(), c.tIndex());
        return spec == null ? 0 : spec.baseFieldsPerHour();
    }

    private String summarise(List<AnalysisResult.Candidate> top, double distanceFields) {
        if (top.isEmpty()) return "no match";
        AnalysisResult.Candidate best = top.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(best.unitName());
        if (distanceFields > 20.0) {
            sb.append(", TS ").append(best.tsLevel());
        }
        boolean wroteBoots = false;
        if (best.bootsBonus() > 0.0) {
            sb.append(" with +").append((int) Math.round(best.bootsBonus() * 100.0)).append("% boots");
            wroteBoots = true;
        }
        if (Math.abs(best.artifactMultiplier() - 1.0) > ARTIFACT_EPSILON) {
            sb.append(wroteBoots ? " and " : " with ");
            sb.append(formatArtifact(best.artifactMultiplier())).append(" artifact");
        }
        if (top.size() > 1) {
            sb.append(" (").append(top.size() - 1).append(" alt)");
        }
        return sb.toString();
    }

    /** "3x" / "0.5x" — matches the in-game picker labels. */
    private static String formatArtifact(double m) {
        // Whole numbers (1, 2, 3) render without trailing zero.
        if (Math.abs(m - Math.round(m)) < ARTIFACT_EPSILON) {
            return Math.round(m) + "x";
        }
        return String.format(java.util.Locale.ROOT, "%.2gx", m);
    }
}
