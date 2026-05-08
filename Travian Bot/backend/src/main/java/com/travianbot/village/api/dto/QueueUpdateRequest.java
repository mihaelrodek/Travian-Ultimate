package com.travianbot.village.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Replace the full queue of a village. Simpler than incremental add/remove endpoints
 * for a v1 editor where the user reorders rows client-side and hits "Save".
 */
public record QueueUpdateRequest(
        @NotNull @Valid List<BuildingQueueItemDto> items
) {
}
