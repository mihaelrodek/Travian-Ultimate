package com.travianbot.village.api.dto;

import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Combined request/response DTO for queue items.
 * <p>
 * Request-side: {@code id}, {@code status}, {@code createdAt}, {@code completedAt},
 * {@code resultMessage} are ignored — the server owns those.
 */
public record BuildingQueueItemDto(
        Long id,
        @NotBlank @Size(max = 64) String buildingType,
        @Min(1) @Max(40) Integer slot,
        @Min(1) @Max(20) int targetLevel,
        int orderIndex,
        QueueStatus status,
        Instant createdAt,
        Instant completedAt,
        Instant finishesAt,
        String resultMessage
) {
    public static BuildingQueueItemDto from(BuildingQueueItem item) {
        return new BuildingQueueItemDto(
                item.getId(),
                item.getBuildingType(),
                item.getSlot(),
                item.getTargetLevel(),
                item.getOrderIndex(),
                item.getStatus(),
                item.getCreatedAt(),
                item.getCompletedAt(),
                item.getFinishesAt(),
                item.getResultMessage()
        );
    }
}
