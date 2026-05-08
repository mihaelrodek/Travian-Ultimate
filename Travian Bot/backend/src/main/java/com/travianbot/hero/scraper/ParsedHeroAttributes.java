package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.HeroProductionType;
import com.travianbot.hero.domain.HeroStatus;

import java.time.Instant;

/**
 * Snapshot of the hero/attributes page. Every field is nullable because skins
 * vary and we'd rather carry forward what we got than reject a partial parse.
 */
public record ParsedHeroAttributes(
        String name,
        Integer level,
        Long experience,
        Long experienceForNext,
        Integer health,
        Integer regenPerHour,
        Integer freePoints,
        Integer pointsFightingStrength,
        Integer pointsOffBonus,
        Integer pointsDefBonus,
        Integer pointsResources,
        HeroStatus status,
        HeroProductionType productionType,
        Long homeVillageDid,
        Long currentVillageDid,
        Instant reviveAt
) {
}
