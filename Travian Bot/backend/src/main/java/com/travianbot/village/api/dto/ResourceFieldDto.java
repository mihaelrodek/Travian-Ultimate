package com.travianbot.village.api.dto;

import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.ResourceType;

import java.time.Instant;

public record ResourceFieldDto(
        Long id,
        int slotId,
        int gid,
        ResourceType type,
        int level,
        boolean underConstruction,
        Instant updatedAt
) {
    public static ResourceFieldDto from(ResourceField f) {
        return new ResourceFieldDto(
                f.getId(),
                f.getSlotId(),
                f.getGid(),
                f.getType(),
                f.getLevel(),
                f.isUnderConstruction(),
                f.getUpdatedAt()
        );
    }
}
