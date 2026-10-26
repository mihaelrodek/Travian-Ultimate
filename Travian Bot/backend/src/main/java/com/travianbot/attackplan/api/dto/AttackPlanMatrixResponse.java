package com.travianbot.attackplan.api.dto;

import com.travianbot.attackplan.service.AttackPlanService.MatrixResult;

import java.time.Instant;
import java.util.List;

/**
 * Flat matrix wire shape — the UI renders this as a 2D grid with sources on
 * one axis and targets on the other.
 * <p>
 * {@link #effectiveSpeed} already folds in the server-speed multiplier, so
 * the UI doesn't have to look up the server row separately to label the
 * speed column.
 */
public record AttackPlanMatrixResponse(
        Long planId,
        String planName,
        Instant landingAt,
        Double baseSpeedFieldsPerHour,
        int serverSpeedMultiplier,
        Double effectiveSpeed,
        List<SourceRow> sources,
        List<TargetRow> targets,
        /** Outer list: one row per source; inner list: one cell per target. */
        List<List<CellResponse>> rows
) {
    public record SourceRow(Long villageId, String villageName, int x, int y) {}

    public record TargetRow(Long targetId, int x, int y, String label) {}

    public record CellResponse(
            double distance,
            long travelSeconds,
            Instant launchAt,
            Long lateBySeconds,
            /**
             * 1-based wave number for this source within its target column.
             * Wave 1 = first to SEND = first to LAND (Travian's tiebreaker
             * when multiple attacks land at the same instant is send order).
             * Null when the plan has no landingAt — wave order is undefined
             * without it.
             */
            Integer sendOrder
    ) {}

    public static AttackPlanMatrixResponse from(MatrixResult m) {
        List<SourceRow> sources = m.sources().stream()
                .map(s -> new SourceRow(s.villageId(), s.villageName(), s.x(), s.y()))
                .toList();
        List<TargetRow> targets = m.targets().stream()
                .map(t -> new TargetRow(t.targetId(), t.x(), t.y(), t.label()))
                .toList();
        List<List<CellResponse>> rows = m.rows().stream()
                .map(row -> row.stream()
                        .map(c -> new CellResponse(c.distance(), c.travelSeconds(),
                                c.launchAt(), c.lateBySeconds(), c.sendOrder()))
                        .toList())
                .toList();
        return new AttackPlanMatrixResponse(
                m.plan().getId(),
                m.plan().getName(),
                m.plan().getLandingAt(),
                m.plan().getSpeedFieldsPerHour(),
                m.serverSpeedMultiplier(),
                m.effectiveSpeed(),
                sources,
                targets,
                rows
        );
    }
}
