package com.travianbot.farmlist.api.dto;

import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.domain.FarmlistTargetMetric;

import java.time.Instant;

/**
 * Per-target analytics row shown in the FarmlistAnalyticsTab table.
 * <p>
 * Targets that haven't been raided yet get a zero-defaulted shape — the UI
 * always sees a row per target, never has to handle "missing metric".
 */
public record FarmlistTargetMetricResponse(
        Long farmlistTargetId,
        Integer targetX,
        Integer targetY,
        String targetName,
        Integer totalRaids,
        Long totalResourcesLooted,
        Integer totalOurTroopsLost,
        Integer consecutiveLossRaids,
        String trafficLight,
        Boolean prunCandidate,
        Instant lastRaidAt,
        Instant lastFullHaulAt
) {
    /**
     * Build a response from a target + its (possibly-null) metric row. When
     * the metric is null the response has zero/default values so the UI can
     * always render the row.
     */
    public static FarmlistTargetMetricResponse from(FarmlistTarget target, FarmlistTargetMetric metric) {
        if (metric == null) {
            return new FarmlistTargetMetricResponse(
                    target.getId(),
                    target.getCoordinateX(),
                    target.getCoordinateY(),
                    target.getName(),
                    0,
                    0L,
                    0,
                    0,
                    "GREEN",
                    false,
                    null,
                    null
            );
        }
        return new FarmlistTargetMetricResponse(
                target.getId(),
                target.getCoordinateX(),
                target.getCoordinateY(),
                target.getName(),
                metric.getTotalRaids(),
                metric.getTotalResourcesLooted(),
                metric.getTotalOurTroopsLost(),
                metric.getConsecutiveLossRaids(),
                metric.getTrafficLight(),
                metric.getPrunCandidate(),
                metric.getLastRaidAt(),
                metric.getLastFullHaulAt()
        );
    }
}
