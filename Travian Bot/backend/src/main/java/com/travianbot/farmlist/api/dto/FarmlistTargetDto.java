package com.travianbot.farmlist.api.dto;

import com.travianbot.farmlist.domain.FarmlistTarget;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record FarmlistTargetDto(
        Long id,
        @Size(max = 100) String name,
        @NotNull @Min(-800) @Max(800) Integer coordinateX,
        @NotNull @Min(-800) @Max(800) Integer coordinateY,
        @NotNull String troopsJson,
        boolean enabled,
        Long travianTargetId,
        Instant lastSentAt
) {
    public static FarmlistTargetDto from(FarmlistTarget t) {
        return new FarmlistTargetDto(
                t.getId(),
                t.getName(),
                t.getCoordinateX(),
                t.getCoordinateY(),
                t.getTroopsJson(),
                t.isEnabled(),
                t.getTravianTargetId(),
                t.getLastSentAt()
        );
    }
}
