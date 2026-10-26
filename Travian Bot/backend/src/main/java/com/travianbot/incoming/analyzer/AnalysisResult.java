package com.travianbot.incoming.analyzer;

import java.util.List;

/**
 * Result of running {@link TournamentSquareAnalyzer#analyse} on one
 * incoming wave.
 * <p>
 * {@code observedFieldsPerHour} is the raw observation (distance / hours);
 * {@code candidates} contains up to {@code TOP_K=3} ranked
 * {(unit, TS level, boots bonus, artifact multiplier)} combinations that
 * reproduce that speed within tolerance. {@code summary} is a one-line
 * human-readable distillation of the top candidate — what you'd want to see
 * in a list row.
 * <p>
 * The whole record is JSON-serialised onto {@code IncomingAttack.analysisJson}
 * by Jackson with no custom mixins; the frontend parses the same shape via
 * {@code types/incoming.ts}.
 */
public record AnalysisResult(
        double observedFieldsPerHour,
        List<Candidate> candidates,
        String summary
) {
    /**
     * One scored {(unit, TS level, boots, artifact)} hypothesis.
     * <p>
     * Semantics of the modifier fields:
     * <ul>
     *   <li>{@code bootsBonus} — additive bonus (0.0 / 0.25 / 0.5 / 0.75) i.e.
     *       speed-after = base × (1 + bootsBonus).</li>
     *   <li>{@code artifactMultiplier} — direct multiplier matching the
     *       in-game picker (0.33 / 0.5 / 0.67 / 1.0 / 1.5 / 2.0 / 3.0). 1.0
     *       means "no artifact". The solver does NOT iterate over this field
     *       — it only takes the value the caller supplied (or 1.0 by
     *       default).</li>
     * </ul>
     */
    public record Candidate(
            int tribeId,
            int tIndex,
            String unitName,
            String role,
            int tsLevel,
            double bootsBonus,
            double artifactMultiplier,
            double theoreticalTime,   // seconds
            double deviation,         // fractional, e.g. 0.012 == 1.2%
            int modifierCount         // count of non-zero modifiers (boots / artifact!=1.0 / ts>0)
    ) {
    }
}
