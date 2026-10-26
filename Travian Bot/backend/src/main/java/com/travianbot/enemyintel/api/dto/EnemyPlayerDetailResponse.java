package com.travianbot.enemyintel.api.dto;

import com.travianbot.enemyintel.domain.EnemyPlayer;
import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;

import java.util.List;

/**
 * Detail view for the side panel — the summary {@link EnemyPlayer} plus its
 * 20 most recent snapshots (timeline order: newest first).
 */
public record EnemyPlayerDetailResponse(
        EnemyPlayerResponse player,
        List<EnemyProfileSnapshotResponse> snapshots
) {

    public static EnemyPlayerDetailResponse from(EnemyPlayer player, List<EnemyProfileSnapshot> snapshots) {
        return new EnemyPlayerDetailResponse(
                EnemyPlayerResponse.from(player),
                snapshots.stream().map(EnemyProfileSnapshotResponse::from).toList()
        );
    }
}
