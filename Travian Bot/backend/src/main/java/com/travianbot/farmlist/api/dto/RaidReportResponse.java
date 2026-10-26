package com.travianbot.farmlist.api.dto;

import com.travianbot.farmlist.domain.RaidReport;

import java.time.Instant;

/**
 * DTO mirror of {@link RaidReport} for the frontend. Excludes the internal
 * {@code createdAt} timestamp — the UI cares about {@code raidReturnedAt}
 * for "when did the loot land", not the row's bookkeeping creation time.
 */
public record RaidReportResponse(
        Long id,
        Long accountId,
        Long farmlistId,
        Long farmlistTargetId,
        Long travianReportId,
        Instant raidLaunchedAt,
        Instant raidReturnedAt,
        Integer targetX,
        Integer targetY,
        String targetVillageName,
        Integer woodLooted,
        Integer clayLooted,
        Integer ironLooted,
        Integer cropLooted,
        Integer carryCapacityTotal,
        Boolean wasFullHaul,
        Integer ourTroopsLost,
        Integer enemyTroopsLost,
        String outcome
) {
    public static RaidReportResponse from(RaidReport r) {
        return new RaidReportResponse(
                r.getId(),
                r.getAccountId(),
                r.getFarmlistId(),
                r.getFarmlistTargetId(),
                r.getTravianReportId(),
                r.getRaidLaunchedAt(),
                r.getRaidReturnedAt(),
                r.getTargetX(),
                r.getTargetY(),
                r.getTargetVillageName(),
                r.getWoodLooted(),
                r.getClayLooted(),
                r.getIronLooted(),
                r.getCropLooted(),
                r.getCarryCapacityTotal(),
                r.getWasFullHaul(),
                r.getOurTroopsLost(),
                r.getEnemyTroopsLost(),
                r.getOutcome()
        );
    }
}
