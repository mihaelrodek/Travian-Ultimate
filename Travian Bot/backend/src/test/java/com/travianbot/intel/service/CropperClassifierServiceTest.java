package com.travianbot.intel.service;

import com.travianbot.intel.domain.MapVillage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-function tests for {@link CropperClassifierService#classify}. No Spring
 * context — just feeds synthetic {@link MapVillage} rows through the bucket
 * decision and asserts the resulting label. The classifier is the heart of
 * the cropper-finder feature so it's worth covering every branch.
 * <p>
 * Lives in the same package as the SUT because {@link CropperClassifierService#classify}
 * is deliberately package-private — the javadoc on the method calls out that
 * its ordering is internal and not API surface.
 */
class CropperClassifierServiceTest {

    private final CropperClassifierService classifier = new CropperClassifierService(null, null);

    @Test
    void highCapitalAboveStrongPopThresholdIsHigh15c() {
        MapVillage v = newVillage(1700, true);
        assertThat(classifier.classify(v, 5)).isEqualTo(CropperClassifierService.HIGH_15C);
    }

    @Test
    void capitalBetween700And1500IsLikely15c() {
        MapVillage v = newVillage(900, true);
        assertThat(classifier.classify(v, 3)).isEqualTo(CropperClassifierService.LIKELY_15C);
    }

    @Test
    void capitalAtExactly700IsOnlyPossibleNotLikely() {
        // 700 is the LIKELY threshold's strict-greater-than cutoff — at exactly
        // 700 we should fall through to POSSIBLE_15C, not LIKELY_15C.
        MapVillage v = newVillage(700, true);
        assertThat(classifier.classify(v, 1)).isEqualTo(CropperClassifierService.POSSIBLE_15C);
    }

    @Test
    void smallCapitalIsPossible15c() {
        MapVillage v = newVillage(150, true);
        assertThat(classifier.classify(v, 1)).isEqualTo(CropperClassifierService.POSSIBLE_15C);
    }

    @Test
    void nonCapitalHighPopWithEnoughVillagesIsLikely9c() {
        MapVillage v = newVillage(1200, false);
        assertThat(classifier.classify(v, 4)).isEqualTo(CropperClassifierService.LIKELY_9C);
    }

    @Test
    void nonCapitalHighPopButFewVillagesIsUnknown() {
        // Heuristic guard: a high-pop non-capital from a tiny account is more
        // likely a second city than a cropper. Needs >= 4 villages to qualify.
        MapVillage v = newVillage(1200, false);
        assertThat(classifier.classify(v, 2)).isEqualTo(CropperClassifierService.UNKNOWN);
    }

    @Test
    void nonCapitalLowPopIsUnknown() {
        MapVillage v = newVillage(500, false);
        assertThat(classifier.classify(v, 5)).isEqualTo(CropperClassifierService.UNKNOWN);
    }

    @Test
    void zeroPopulationCapitalStillPossible15c() {
        // Defensive: a freshly settled capital has 0 pop on day 1 but is still
        // worth flagging as POSSIBLE for the user to investigate.
        MapVillage v = newVillage(0, true);
        assertThat(classifier.classify(v, 1)).isEqualTo(CropperClassifierService.POSSIBLE_15C);
    }

    /**
     * Build a {@link MapVillage} with just the fields the classifier touches.
     * Coordinates and timestamps are irrelevant for the unit under test.
     */
    private static MapVillage newVillage(int population, boolean capital) {
        MapVillage v = new MapVillage(1L, 100L, 200L, "test", 0, 0, population);
        v.setIsCapital(capital);
        return v;
    }
}
