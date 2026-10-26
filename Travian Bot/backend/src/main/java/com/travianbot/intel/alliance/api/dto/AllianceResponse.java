package com.travianbot.intel.alliance.api.dto;

import com.travianbot.intel.alliance.domain.MapAlliance;

import java.time.Instant;

/**
 * Base shape for an alliance row in list responses. Detail responses extend
 * this with description, bonuses, and member roster — see
 * {@link AllianceDetailResponse}.
 */
public record AllianceResponse(
        Long id,
        Long serverId,
        Long travianAid,
        String name,
        Integer memberCount,
        Long totalPopulation,
        Integer averagePopulation,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastDetailScrapedAt
) {
    public static AllianceResponse from(MapAlliance a) {
        return new AllianceResponse(
                a.getId(),
                a.getServerId(),
                a.getTravianAid(),
                a.getName(),
                a.getMemberCount(),
                a.getTotalPopulation(),
                a.getAveragePopulation(),
                a.getFirstSeenAt(),
                a.getLastSeenAt(),
                a.getLastDetailScrapedAt()
        );
    }
}
