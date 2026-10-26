package com.travianbot.intel.api.dto;

import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.service.InactiveSearchService.PlayerDetail;

import java.time.Instant;
import java.util.List;

/**
 * Full player view: header + every tracked village. Includes both 7d and 30d
 * snapshots so the detail panel can render a small population trend.
 */
public record InactivePlayerDetailResponse(
        Long id,
        Long travianUid,
        String name,
        String allianceName,
        Long allianceId,
        Integer tribeId,
        Integer currentPopulation,
        Integer populationSnapshot7dAgo,
        Integer populationSnapshot30dAgo,
        Integer villageCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant lastPopChangeAt,
        boolean isInactive,
        List<VillageDto> villages
) {
    public static InactivePlayerDetailResponse from(PlayerDetail detail) {
        MapPlayer p = detail.player();
        return new InactivePlayerDetailResponse(
                p.getId(),
                p.getTravianUid(),
                p.getPlayerName(),
                p.getAllianceName(),
                p.getTravianAid(),
                p.getTribeId(),
                p.getCurrentPopulation(),
                p.getPopulationSnapshot7dAgo(),
                p.getPopulationSnapshot30dAgo(),
                p.getVillageCount(),
                p.getFirstSeenAt(),
                p.getLastSeenAt(),
                p.getLastPopChangeAt(),
                Boolean.TRUE.equals(p.getIsInactive()),
                detail.villages().stream().map(VillageDto::from).toList()
        );
    }

    public record VillageDto(
            Long id,                  // local MapVillage row id — used by the chart endpoint
            Long travianVid,
            String name,
            int x,
            int y,
            int population,
            boolean isCapital,
            Instant lastSeenAt
    ) {
        public static VillageDto from(MapVillage v) {
            return new VillageDto(
                    v.getId(),
                    v.getTravianVid(),
                    v.getName(),
                    v.getX(),
                    v.getY(),
                    v.getPopulation(),
                    Boolean.TRUE.equals(v.getIsCapital()),
                    v.getLastSeenAt()
            );
        }
    }
}
