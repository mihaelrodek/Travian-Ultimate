package com.travianbot.enemyintel.api.dto;

import com.travianbot.enemyintel.domain.EnemyPlayer;

import java.time.Duration;
import java.time.Instant;

/**
 * Wire shape for an {@link EnemyPlayer} list row. Adds the derived
 * {@code daysSinceLastScrape} so the UI can render the staleness chip
 * without having to do TZ math client-side.
 */
public record EnemyPlayerResponse(
        Long id,
        Long accountId,
        Long travianUid,
        String playerName,
        Long allianceTravianId,
        String allianceName,
        Integer tribeId,
        Integer currentPopulation,
        Integer villageCount,
        Integer heroLevel,
        Integer heroXp,
        Integer heroProductionBonus,
        Long lastInteractionAttackId,
        Instant firstSeenAt,
        Instant lastScrapedAt,
        Instant lastChangeAt,
        Long daysSinceLastScrape,
        Instant createdAt,
        Instant updatedAt
) {

    public static EnemyPlayerResponse from(EnemyPlayer p) {
        Long days = null;
        if (p.getLastScrapedAt() != null) {
            long secs = Duration.between(p.getLastScrapedAt(), Instant.now()).getSeconds();
            // Clamp to non-negative — handles freshly-created rows where
            // lastScrapedAt is "now" or wall-clock skew puts it microseconds
            // in the future.
            days = Math.max(0L, secs / 86_400L);
        }
        return new EnemyPlayerResponse(
                p.getId(),
                p.getAccountId(),
                p.getTravianUid(),
                p.getPlayerName(),
                p.getAllianceTravianId(),
                p.getAllianceName(),
                p.getTribeId(),
                p.getCurrentPopulation(),
                p.getVillageCount(),
                p.getHeroLevel(),
                p.getHeroXp(),
                p.getHeroProductionBonus(),
                p.getLastInteractionAttackId(),
                p.getFirstSeenAt(),
                p.getLastScrapedAt(),
                p.getLastChangeAt(),
                days,
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
