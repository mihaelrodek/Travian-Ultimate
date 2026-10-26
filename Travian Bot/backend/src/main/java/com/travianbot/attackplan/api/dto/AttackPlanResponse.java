package com.travianbot.attackplan.api.dto;

import com.travianbot.attackplan.domain.AttackPlan;

import java.time.Instant;
import java.util.List;

public record AttackPlanResponse(
        Long id,
        Long accountId,
        String name,
        Double speedFieldsPerHour,
        Instant landingAt,
        Instant createdAt,
        Instant updatedAt,
        List<TargetResponse> targets,
        List<SourceResponse> sources
) {
    public record TargetResponse(Long id, int x, int y, String label, int orderIndex) {}

    public record SourceResponse(Long id, Long villageId, int orderIndex) {}

    public static AttackPlanResponse from(AttackPlan plan) {
        return new AttackPlanResponse(
                plan.getId(),
                plan.getAccountId(),
                plan.getName(),
                plan.getSpeedFieldsPerHour(),
                plan.getLandingAt(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getTargets().stream().map(t -> new TargetResponse(
                        t.getId(), t.getX(), t.getY(), t.getLabel(), t.getOrderIndex()
                )).toList(),
                plan.getSources().stream().map(s -> new SourceResponse(
                        s.getId(), s.getVillageId(), s.getOrderIndex()
                )).toList()
        );
    }
}
