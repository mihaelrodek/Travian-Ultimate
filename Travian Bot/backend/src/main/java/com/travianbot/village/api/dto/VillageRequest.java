package com.travianbot.village.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Incoming create/update body for a village. {@code accountId} is required on create
 * and ignored on update (the controller takes the id from the path).
 */
public record VillageRequest(
        Long accountId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(-800) @Max(800) Integer coordinateX,
        @NotNull @Min(-800) @Max(800) Integer coordinateY,
        boolean capital,
        Integer population
) {
}
