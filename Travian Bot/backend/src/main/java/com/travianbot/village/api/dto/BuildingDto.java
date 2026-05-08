package com.travianbot.village.api.dto;

import com.travianbot.village.domain.Building;

import java.time.Instant;

public record BuildingDto(
        Long id,
        int slotId,
        int gid,
        String name,
        int level,
        boolean underConstruction,
        Instant updatedAt
) {
    public static BuildingDto from(Building b) {
        return new BuildingDto(
                b.getId(),
                b.getSlotId(),
                b.getGid(),
                b.getName(),
                b.getLevel(),
                b.isUnderConstruction(),
                b.getUpdatedAt()
        );
    }
}
