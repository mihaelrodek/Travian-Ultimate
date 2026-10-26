package com.travianbot.attackplan.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Request body for plan create + update. Pass the WHOLE intended state every
 * time — there's no patch surface; the service rewrites targets and sources
 * atomically.
 */
public record AttackPlanRequest(
        String name,
        Double speedFieldsPerHour,
        Instant landingAt,
        List<TargetDto> targets,
        List<Long> sourceVillageIds
) {
    public record TargetDto(int x, int y, String label) {}
}
