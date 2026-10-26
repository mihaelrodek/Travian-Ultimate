package com.travianbot.intel.api.dto;

import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.service.InactiveSearchService.SearchResult;

/**
 * Row shape for the inactive-search results table.
 * <p>
 * Only the fields the table actually renders are included — keeps payloads
 * lean across paginated requests. Population delta (current vs 7d ago) is
 * exposed so the UI can render an up/down arrow without re-deriving it.
 */
public record InactivePlayerResponse(
        Long id,
        Long travianUid,
        String name,
        String allianceName,
        Integer tribeId,
        Integer currentPopulation,
        Integer populationDelta7d,
        Integer villageCount,
        Long daysSincePopChange,
        Double nearestVillageDistance,
        boolean isInactive
) {
    public static InactivePlayerResponse from(SearchResult result) {
        MapPlayer p = result.player();
        Integer delta = (p.getPopulationSnapshot7dAgo() == null) ? null
                : p.getCurrentPopulation() - p.getPopulationSnapshot7dAgo();
        return new InactivePlayerResponse(
                p.getId(),
                p.getTravianUid(),
                p.getPlayerName(),
                p.getAllianceName(),
                p.getTribeId(),
                p.getCurrentPopulation(),
                delta,
                p.getVillageCount(),
                result.daysSincePopChange(),
                result.nearestVillageDistance(),
                Boolean.TRUE.equals(p.getIsInactive())
        );
    }
}
