package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.AdventureDifficulty;

import java.time.Instant;

public record ParsedHeroAdventure(
        int targetX,
        int targetY,
        AdventureDifficulty difficulty,
        Instant endAt,
        Long mapId
) {
}
