package com.travianbot.troops.api.dto;

import com.travianbot.troops.domain.TroopBuilding;
import com.travianbot.troops.domain.TroopQueueItem;
import com.travianbot.village.domain.QueueStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Combined request/response DTO for troop queue rows.
 * <p>
 * Request-side: {@code id}, {@code status}, {@code createdAt}, {@code completedAt},
 * {@code resultMessage} are ignored — the server owns those.
 */
public record TroopQueueItemDto(
        Long id,
        @NotBlank @Size(max = 64) String troopCode,
        @NotNull TroopBuilding building,
        /* Upper bound is generous on purpose — Travian cap is 99,999 per click, but we also
           accept total training plans above that (they'll be sent in chunks by a future
           trainer). Keeping the guard at 1,000,000 catches obvious typos without blocking
           realistic endgame runs. */
        @Min(1) @Max(1_000_000) int quantity,
        int orderIndex,
        QueueStatus status,
        Instant createdAt,
        Instant completedAt,
        String resultMessage
) {
    public static TroopQueueItemDto from(TroopQueueItem item) {
        return new TroopQueueItemDto(
                item.getId(),
                item.getTroopCode(),
                item.getBuilding(),
                item.getQuantity(),
                item.getOrderIndex(),
                item.getStatus(),
                item.getCreatedAt(),
                item.getCompletedAt(),
                item.getResultMessage()
        );
    }
}
