package com.travianbot.hero.api.dto;

import com.travianbot.hero.domain.AdventureDifficulty;
import com.travianbot.hero.domain.HeroAdventure;

import java.time.Instant;

/**
 * UI-facing view of a single hero adventure row.
 */
public record HeroAdventureResponse(
        Long id,
        int targetX,
        int targetY,
        AdventureDifficulty difficulty,
        Instant endAt,
        Long mapId
) {
    public static HeroAdventureResponse from(HeroAdventure a) {
        return new HeroAdventureResponse(
                a.getId(),
                a.getTargetX(),
                a.getTargetY(),
                a.getDifficulty(),
                a.getEndAt(),
                a.getMapId()
        );
    }
}
