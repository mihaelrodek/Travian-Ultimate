package com.travianbot.server.api.dto;

import com.travianbot.server.domain.Server;

import java.time.Instant;

/**
 * Wire shape for one tracked Travian {@link Server}.
 * <p>
 * {@code accountCount} is denormalised here for the picker — saves the
 * frontend from cross-referencing the accounts list to show
 * "ts1.travian.com · 2 of your accounts on it".
 * <p>
 * {@code speedMultiplier} / {@code mapRadius} are derived from the canonical
 * URL pattern on save (see
 * {@link com.travianbot.server.service.ServerService#parseSpeedFromUrl(String)}).
 * They drive the incoming-attacks Tournament-Square solver: the analyzer
 * needs the server speed to convert observed fields/hour back to the unit
 * base table.
 */
public record ServerResponse(
        Long id,
        String url,
        String name,
        String region,
        String gameWorld,
        boolean active,
        Instant lastMapSyncedAt,
        Integer lastMapSyncPlayerCount,
        Integer speedMultiplier,
        Integer mapRadius,
        Long accountCount,
        Instant createdAt
) {
    public static ServerResponse from(Server s, long accountCount) {
        return new ServerResponse(
                s.getId(),
                s.getUrl(),
                s.getName(),
                s.getRegion(),
                s.getGameWorld(),
                Boolean.TRUE.equals(s.getActive()),
                s.getLastMapSyncedAt(),
                s.getLastMapSyncPlayerCount(),
                s.getSpeedMultiplier(),
                s.getMapRadius(),
                accountCount,
                s.getCreatedAt()
        );
    }
}
