package com.travianbot.enemyintel.api.dto;

import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;

import java.time.Instant;

/**
 * Wire shape for a single {@link EnemyProfileSnapshot}. Mirrors the entity
 * directly — there's no derived field worth computing server-side here
 * because the diff is already pre-stored on the row.
 */
public record EnemyProfileSnapshotResponse(
        Long id,
        Long enemyPlayerId,
        Instant capturedAt,
        Integer heroLevel,
        Integer heroXp,
        Integer heroProductionBonus,
        Integer currentPopulation,
        Integer villageCount,
        Long allianceTravianId,
        String allianceName,
        String diffSummary
) {

    public static EnemyProfileSnapshotResponse from(EnemyProfileSnapshot s) {
        return new EnemyProfileSnapshotResponse(
                s.getId(),
                s.getEnemyPlayerId(),
                s.getCapturedAt(),
                s.getHeroLevel(),
                s.getHeroXp(),
                s.getHeroProductionBonus(),
                s.getCurrentPopulation(),
                s.getVillageCount(),
                s.getAllianceTravianId(),
                s.getAllianceName(),
                s.getDiffSummary()
        );
    }
}
