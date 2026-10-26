package com.travianbot.enemyintel;

import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;
import com.travianbot.enemyintel.service.EnemyChangeDetector;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level coverage for {@link EnemyChangeDetector#describe}. The detector
 * is the single most-narrated user-facing surface of the module — a hero
 * level drop ends up in the BotEvent log and in the timeline diff column,
 * so we pin the exact string format here.
 */
class EnemyChangeDetectorTest {

    private final EnemyChangeDetector detector = new EnemyChangeDetector();

    @Test
    void heroLevelDropIsNarratedWithDirection() {
        EnemyProfileSnapshot prev = snapshot(1L);
        prev.setHeroLevel(25);
        prev.setVillageCount(8);
        prev.setCurrentPopulation(4200);

        EnemyProfileSnapshot next = snapshot(1L);
        next.setHeroLevel(24); // hero died
        next.setVillageCount(8);
        next.setCurrentPopulation(4200);

        String diff = detector.describe(prev, next);

        // Hero must be reported with both old and new value AND the signed
        // delta — that's how the UI renders the red "dropped" chip.
        assertThat(diff)
                .contains("Hero")
                .contains("25")
                .contains("24")
                .contains("-1");
        // Quiet fields stay quiet — pop is unchanged and village count is
        // unchanged, so neither should appear in the output string.
        assertThat(diff).doesNotContain("Pop");
        assertThat(diff).doesNotContain("Villages");
    }

    @Test
    void noChangeReturnsEmptyString() {
        EnemyProfileSnapshot prev = snapshot(1L);
        prev.setHeroLevel(20);
        prev.setVillageCount(5);
        prev.setCurrentPopulation(2500);

        EnemyProfileSnapshot next = snapshot(1L);
        next.setHeroLevel(20);
        next.setVillageCount(5);
        next.setCurrentPopulation(2520); // delta below noise floor

        assertThat(detector.describe(prev, next)).isEmpty();
    }

    @Test
    void firstSnapshotReturnsEmptyString() {
        // prev = null means "no prior snapshot to compare against".
        EnemyProfileSnapshot next = snapshot(1L);
        next.setHeroLevel(10);
        assertThat(detector.describe(null, next)).isEmpty();
    }

    private static EnemyProfileSnapshot snapshot(Long enemyId) {
        return new EnemyProfileSnapshot(enemyId, Instant.now());
    }
}
