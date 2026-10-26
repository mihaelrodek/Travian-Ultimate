package com.travianbot.village.api.dto;

import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.Village;
import com.travianbot.village.domain.VillageType;

import java.time.Instant;
import java.util.List;

public record VillageResponse(
        Long id,
        Long accountId,
        String name,
        int coordinateX,
        int coordinateY,
        boolean capital,
        Integer population,
        Long travianVillageId,
        /**
         * Name of the in-game village group (from Travian's own "Village groups" feature) or
         * {@code null} for ungrouped. Read-only on our side.
         */
        String groupName,
        /**
         * User-chosen position in the Villages sidebar. Zero-based. Rows that have
         * never been manually reordered share {@code 0} and tiebreak on creation
         * time — see {@code VillageRepository}.
         */
        int orderIndex,
        /**
         * Manual Tournament Square level set by the user (0..20). Null when
         * unset — the analyzer brute-forces TS in that case. Used to pin the
         * inverse solver and (future) return-catch math.
         */
        Integer tsLevel,
        /**
         * User-applied role tag (off hammer, deff, pve, …). Null means
         * unclassified. Persisted on Village so a Clear-all of troop
         * snapshots doesn't lose it.
         */
        VillageType villageType,
        Instant lastScrapedAt,
        Instant createdAt,
        Instant updatedAt,
        List<BuildingQueueItemDto> queue,
        List<ResourceFieldDto> resourceFields,
        List<BuildingDto> buildings
) {
    /** Convenience factory for the list endpoint — scrape data is omitted. */
    public static VillageResponse from(Village village) {
        return fromInternal(village, List.of(), List.of());
    }

    /** Full factory for the detail endpoint — needs fields + buildings loaded. */
    public static VillageResponse from(Village village, List<ResourceField> fields, List<Building> buildings) {
        return fromInternal(
                village,
                fields.stream().map(ResourceFieldDto::from).toList(),
                buildings.stream().map(BuildingDto::from).toList()
        );
    }

    private static VillageResponse fromInternal(
            Village village,
            List<ResourceFieldDto> fields,
            List<BuildingDto> buildings
    ) {
        return new VillageResponse(
                village.getId(),
                village.getAccount().getId(),
                village.getName(),
                village.getCoordinateX(),
                village.getCoordinateY(),
                village.isCapital(),
                village.getPopulation(),
                village.getTravianVillageId(),
                village.getGroupName(),
                village.getOrderIndex(),
                village.getTsLevel(),
                village.getVillageType(),
                village.getLastScrapedAt(),
                village.getCreatedAt(),
                village.getUpdatedAt(),
                village.getQueue().stream().map(BuildingQueueItemDto::from).toList(),
                fields,
                buildings
        );
    }
}
