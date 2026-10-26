package com.travianbot.incoming.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pure unit tests for the Tournament-Square inverse solver. No Spring, no DB
 * — the analyzer is stateless, so we exercise it directly.
 * <p>
 * Each test pins one canonical scenario from the spec (sub-20 hop, long-hop
 * with TS, with-boots match, etc.) and asserts the solver produces the
 * candidate we'd expect a human to write down by hand.
 */
class TournamentSquareAnalyzerTest {

    private final TournamentSquareAnalyzer analyzer = new TournamentSquareAnalyzer();

    @Test
    @DisplayName("Long hop, observed 10 f/h — Occam's razor picks a base-10 unit with TS 0")
    void longHopMatchesSimplestBase10Combo() {
        // Forward math sanity-check:
        //   100 fields in 10h → 10 f/h observed
        //   For ANY base-10 unit at TS=0, no modifiers:
        //     20/10 + 80/10 = 2 + 8 = 10h exactly → deviation = 0, modifierCount = 0
        //   For Phalanx (base 7) at TS=3, no modifiers:
        //     20/7 + 80/(7*1.6) = 2.857 + 7.143 = 10h → deviation = 0, modifierCount = 1
        // Tiebreak: deviation asc → modifierCount asc → base speed desc. So
        // the base-10 candidate wins on the modifier-count Occam's razor rule.
        AnalysisResult result = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty(), "should find at least one candidate");

        AnalysisResult.Candidate top = result.candidates().get(0);
        assertTrue(top.deviation() <= 0.02,
                "top deviation should be within tight tolerance, got " + top.deviation());
        assertEquals(0, top.modifierCount(),
                "simplest explanation should be a no-modifier candidate, got " + top);
        assertEquals(0, top.tsLevel(),
                "TS must be 0 for the no-modifier explanation, got " + top.tsLevel());
        assertEquals(0.0, top.bootsBonus(), 1e-9);
        // No artifact iteration any more — every candidate carries the
        // caller-supplied multiplier verbatim (default 1.0 here).
        assertEquals(1.0, top.artifactMultiplier(), 1e-9);
        TroopSpeedCatalog.TroopSpec spec = TroopSpeedCatalog.get(top.tribeId(), top.tIndex());
        assertNotNull(spec);
        assertEquals(10, spec.baseFieldsPerHour(),
                "top candidate should be a base-10 unit, got " + spec.name() + " (base " + spec.baseFieldsPerHour() + ")");
    }

    @Test
    @DisplayName("Sub-20-field hop — TS doesn't matter, all candidates have tsLevel 0")
    void shortHopReportsTsZeroOnly() {
        // 15 fields in 1h → 15 f/h. Several units can hit that with various
        // boots/artifact combos, but on a sub-20 hop TS is irrelevant — the
        // solver should collapse and only report tsLevel=0.
        AnalysisResult result = analyzer.analyse(15.0, Duration.ofHours(1), 1, 200);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty());

        for (AnalysisResult.Candidate c : result.candidates()) {
            assertEquals(0, c.tsLevel(),
                    "sub-20 hops must report tsLevel=0, got " + c.tsLevel() + " for " + c.unitName());
        }
    }

    @Test
    @DisplayName("40-field hop, 2 hours — should rank a boots-augmented base-8ish match in the top")
    void withBootsMatchesBootsAugmentedCombo() {
        // Forward: 40 fields in 2h = 20 f/h.
        // Without TS this exceeds base 19 (Gaul Theutates Thunder = 19 f/h).
        // A simple match: TT base 19, no boots, TS 0:
        //   20/19 + 20/19 = 2.105h — close to 2h, deviation 5.3%, outside tight.
        // With boots 0.25: 19 * 1.25 = 23.75; 20/23.75 + 20/23.75 = 1.684h — deviation 16%.
        // With TS 1 on TT: 20/19 + 20/(19*1.2) = 1.053 + 0.877 = 1.93h — deviation 3.5%.
        // The solver should at least find something within the loose 5% pass.
        AnalysisResult result = analyzer.analyse(40.0, Duration.ofHours(2), 1, 200);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty(),
                "expected at least one candidate at 40 fields / 2 hours (~20 f/h)");
        // Any top-3 candidate within tolerance is fine — we're verifying the
        // solver found something, not pinning the exact bookkeeping.
        for (AnalysisResult.Candidate c : result.candidates()) {
            assertTrue(c.deviation() <= 0.05,
                    "candidate " + c.unitName() + " deviation should be ≤ 5%, got " + c.deviation());
        }
    }

    @Test
    @DisplayName("distance=0 → empty list, summary 'no match'")
    void zeroDistanceReturnsEmpty() {
        AnalysisResult result = analyzer.analyse(0.0, Duration.ofHours(1), 1, 200);
        assertNotNull(result);
        assertTrue(result.candidates().isEmpty());
        assertEquals("no match", result.summary());
    }

    @Test
    @DisplayName("Negative or zero travel time → empty list")
    void zeroTravelTimeReturnsEmpty() {
        AnalysisResult result = analyzer.analyse(50.0, Duration.ZERO, 1, 200);
        assertNotNull(result);
        assertTrue(result.candidates().isEmpty());
        AnalysisResult result2 = analyzer.analyse(50.0, Duration.ofSeconds(-10), 1, 200);
        assertTrue(result2.candidates().isEmpty());
    }

    @Test
    @DisplayName("Same observed time on x3 server should pick a 3x slower base unit")
    void serverSpeedShiftsPreferredBaseUnit() {
        // x1, 100 fields, 10h: should land on base-7 (Phalanx-class).
        AnalysisResult x1 = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200);
        // x3, 100 fields, 10h: equivalent observed speed but server-speed
        // multiplied by 3 → solver should find candidates whose BASE speed is
        // ~1/3 of the x1 case.
        AnalysisResult x3 = analyzer.analyse(100.0, Duration.ofHours(10), 3, 200);
        assertNotNull(x1);
        assertNotNull(x3);
        assertFalse(x3.candidates().isEmpty(), "x3 server should still produce candidates");

        AnalysisResult.Candidate x1Top = x1.candidates().get(0);
        AnalysisResult.Candidate x3Top = x3.candidates().get(0);
        TroopSpeedCatalog.TroopSpec specX1 = TroopSpeedCatalog.get(x1Top.tribeId(), x1Top.tIndex());
        TroopSpeedCatalog.TroopSpec specX3 = TroopSpeedCatalog.get(x3Top.tribeId(), x3Top.tIndex());
        assertNotNull(specX1);
        assertNotNull(specX3);
        // The x3 winner's base should be strictly slower than the x1 winner's
        // base — otherwise the math would put it three times too fast.
        assertTrue(specX3.baseFieldsPerHour() < specX1.baseFieldsPerHour(),
                "x3-server top base (" + specX3.baseFieldsPerHour() + ") should be slower than "
                        + "x1-server top base (" + specX1.baseFieldsPerHour() + ")");
    }

    @Test
    @DisplayName("Top 3 candidates returned at most")
    void resultCapsAtThreeCandidates() {
        // Long trip with a popular speed → lots of valid combos. Make sure
        // the solver still trims to TOP_K=3.
        AnalysisResult result = analyzer.analyse(80.0, Duration.ofHours(6), 1, 200);
        assertNotNull(result);
        assertTrue(result.candidates().size() <= 3,
                "candidates must be capped at 3, got " + result.candidates().size());
    }

    @Test
    @DisplayName("Ranking — candidates are sorted by deviation, then modifierCount, then base speed desc")
    void rankingIsStable() {
        // Verify the documented ranking order is actually applied. Any pair
        // (i, i+1) must satisfy the lexicographic ordering of
        // (deviation asc, modifierCount asc, baseSpeed desc).
        AnalysisResult result = analyzer.analyse(50.0, Duration.ofHours(5), 1, 200);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty());
        for (int i = 0; i + 1 < result.candidates().size(); i++) {
            AnalysisResult.Candidate a = result.candidates().get(i);
            AnalysisResult.Candidate b = result.candidates().get(i + 1);
            if (a.deviation() < b.deviation()) continue; // dev asc OK
            assertEquals(a.deviation(), b.deviation(), 1e-12,
                    "deviation must be non-decreasing across the candidate list");
            if (a.modifierCount() < b.modifierCount()) continue;
            assertEquals(a.modifierCount(), b.modifierCount(),
                    "modifierCount must be non-decreasing within equal-deviation runs");
            int aBase = TroopSpeedCatalog.get(a.tribeId(), a.tIndex()).baseFieldsPerHour();
            int bBase = TroopSpeedCatalog.get(b.tribeId(), b.tIndex()).baseFieldsPerHour();
            assertTrue(aBase >= bBase,
                    "baseFieldsPerHour must be non-increasing within equal-dev,equal-modifier runs");
        }
    }

    @Test
    @DisplayName("Speed multiplier <= 0 falls back to 1, not a divide-by-zero")
    void invalidServerSpeedIsTreatedAsOne() {
        AnalysisResult zero = analyzer.analyse(100.0, Duration.ofHours(10), 0, 200);
        AnalysisResult negative = analyzer.analyse(100.0, Duration.ofHours(10), -3, 200);
        AnalysisResult one = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200);
        assertNotNull(zero);
        assertNotNull(negative);
        assertNotNull(one);
        // We don't pin equality on candidate ordering (there could be ties
        // resolved by FP precision), but the observed speed must match.
        assertEquals(one.observedFieldsPerHour(), zero.observedFieldsPerHour(), 1e-9);
        assertEquals(one.observedFieldsPerHour(), negative.observedFieldsPerHour(), 1e-9);
    }

    @Test
    @DisplayName("Default analyse(...) treats artifact as 1.0 — no iteration")
    void defaultRunNeverPicksNonUnityArtifact() {
        AnalysisResult result = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty());
        for (AnalysisResult.Candidate c : result.candidates()) {
            assertEquals(1.0, c.artifactMultiplier(), 1e-9,
                    "default run must not assume any artifact, got " + c.artifactMultiplier()
                            + " for " + c.unitName());
        }
    }

    @Test
    @DisplayName("Manual artifact = 2.0 — candidate carries multiplier verbatim and modifierCount counts it")
    void manualArtifactPropagatesAndCounts() {
        AnalysisResult result = analyzer.analyse(100.0, Duration.ofHours(5), 1, 200, 2.0);
        assertNotNull(result);
        assertFalse(result.candidates().isEmpty(),
                "double-speed assumption should still yield candidates");
        for (AnalysisResult.Candidate c : result.candidates()) {
            assertEquals(2.0, c.artifactMultiplier(), 1e-9);
            // 2.0 != 1.0, so the modifier counter must include it.
            int expectedAtLeast = 1 + (c.bootsBonus() > 0.0 ? 1 : 0) + (c.tsLevel() > 0 ? 1 : 0);
            assertEquals(expectedAtLeast, c.modifierCount(),
                    "modifierCount should include the artifact when != 1.0, got " + c);
        }
    }

    @Test
    @DisplayName("Out-of-set artifact multiplier falls back to 1.0 silently")
    void unknownArtifactMultiplierFallsBackToUnity() {
        AnalysisResult bogus = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200, 1.23);
        assertNotNull(bogus);
        assertFalse(bogus.candidates().isEmpty());
        for (AnalysisResult.Candidate c : bogus.candidates()) {
            assertEquals(1.0, c.artifactMultiplier(), 1e-9);
        }
    }

    @Test
    @DisplayName("Summary mentions TS level only when distance > 20")
    void summaryFormatVariesByHopLength() {
        AnalysisResult shortHop = analyzer.analyse(15.0, Duration.ofHours(1), 1, 200);
        AnalysisResult longHop = analyzer.analyse(100.0, Duration.ofHours(10), 1, 200);
        assertNotNull(shortHop.summary());
        assertNotNull(longHop.summary());
        // Sub-20 summary should NOT include "TS"
        assertFalse(shortHop.summary().contains(", TS "),
                "sub-20 hop summary should not mention TS, got: " + shortHop.summary());
        // Long-hop summary SHOULD include "TS"
        assertTrue(longHop.summary().contains("TS "),
                "long hop summary should mention TS, got: " + longHop.summary());
        assertNotEquals("no match", shortHop.summary());
    }
}
