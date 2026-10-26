package com.travianbot.intel.alliance.api.dto;

import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.domain.MapAllianceMember;

import java.time.Instant;
import java.util.List;

/**
 * Detail-view envelope: the base alliance fields plus the description,
 * bonuses, and (optionally) the member roster from the last detail scrape.
 * <p>
 * {@code members} is empty when no detail scrape has run yet — the caller
 * can tell from {@code lastDetailScrapedAt == null} that the row only has
 * aggregated values from map.sql.
 */
public record AllianceDetailResponse(
        Long id,
        Long serverId,
        Long travianAid,
        String name,
        Integer memberCount,
        Long totalPopulation,
        Integer averagePopulation,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastDetailScrapedAt,
        String description,
        Integer bonusDiplomacy,
        Integer bonusMining,
        Integer bonusScout,
        Integer bonusBrewery,
        String rawBonusesJson,
        List<AllianceMemberDto> members
) {
    public static AllianceDetailResponse from(MapAlliance a, List<MapAllianceMember> members) {
        List<AllianceMemberDto> memberDtos = members == null
                ? List.of()
                : members.stream().map(AllianceMemberDto::from).toList();
        return new AllianceDetailResponse(
                a.getId(),
                a.getServerId(),
                a.getTravianAid(),
                a.getName(),
                a.getMemberCount(),
                a.getTotalPopulation(),
                a.getAveragePopulation(),
                a.getFirstSeenAt(),
                a.getLastSeenAt(),
                a.getLastDetailScrapedAt(),
                a.getDescription(),
                a.getBonusDiplomacy(),
                a.getBonusMining(),
                a.getBonusScout(),
                a.getBonusBrewery(),
                a.getRawBonusesJson(),
                memberDtos
        );
    }
}
