package com.travianbot.hero.api.dto;

import com.travianbot.hero.domain.Hero;
import com.travianbot.hero.domain.HeroAdventure;
import com.travianbot.hero.domain.HeroItem;
import com.travianbot.hero.domain.HeroProductionType;
import com.travianbot.hero.domain.HeroStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete hero payload for the /api/accounts/{id}/hero endpoint.
 * Bundles the attributes, items and adventures so the UI needs a single
 * round-trip to render the whole hero page.
 */
public record HeroResponse(
        Long id,
        Long accountId,
        String name,
        int level,
        long experience,
        Long experienceForNext,
        int health,
        int regenPerHour,
        int freePoints,
        int pointsFightingStrength,
        int pointsOffBonus,
        int pointsDefBonus,
        int pointsResources,
        int adventurePoints,
        HeroStatus status,
        HeroProductionType productionType,
        Long homeVillageDid,
        Long currentVillageDid,
        Instant reviveAt,
        Instant lastScrapedAt,
        List<HeroItemResponse> items,
        List<HeroAdventureResponse> adventures
) {
    public static HeroResponse from(Hero h) {
        List<HeroItemResponse> items = new ArrayList<>(h.getItems().size());
        for (HeroItem it : h.getItems()) items.add(HeroItemResponse.from(it));
        List<HeroAdventureResponse> advs = new ArrayList<>(h.getAdventures().size());
        for (HeroAdventure a : h.getAdventures()) advs.add(HeroAdventureResponse.from(a));

        return new HeroResponse(
                h.getId(),
                h.getAccount().getId(),
                h.getName(),
                h.getLevel(),
                h.getExperience(),
                h.getExperienceForNext(),
                h.getHealth(),
                h.getRegenPerHour(),
                h.getFreePoints(),
                h.getPointsFightingStrength(),
                h.getPointsOffBonus(),
                h.getPointsDefBonus(),
                h.getPointsResources(),
                h.getAdventurePoints(),
                h.getStatus(),
                h.getProductionType(),
                h.getHomeVillageDid(),
                h.getCurrentVillageDid(),
                h.getReviveAt(),
                h.getLastScrapedAt(),
                items,
                advs
        );
    }
}
