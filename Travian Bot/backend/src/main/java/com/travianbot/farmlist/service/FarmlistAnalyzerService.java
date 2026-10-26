package com.travianbot.farmlist.service;

import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.domain.FarmlistTargetMetric;
import com.travianbot.farmlist.domain.RaidReport;
import com.travianbot.farmlist.persistence.FarmlistTargetMetricRepository;
import com.travianbot.farmlist.persistence.FarmlistTargetRepository;
import com.travianbot.farmlist.persistence.RaidReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the per-target {@link FarmlistTargetMetric} rollup.
 * <p>
 * The {@link com.travianbot.farmlist.service.RaidReportScraperService scraper}
 * calls {@link #ingest(RaidReport)} for every newly-saved report. We:
 * <ol>
 *   <li>find-or-create the metric row for the attributed target,</li>
 *   <li>add the report's numbers to the totals,</li>
 *   <li>recompute the traffic-light and prune-candidate flag.</li>
 * </ol>
 * <p>
 * Traffic-light formula lives on the {@link FarmlistTargetMetric} javadoc —
 * keep the documentation and the implementation in lock-step.
 */
@Service
@Transactional
public class FarmlistAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(FarmlistAnalyzerService.class);

    /** Threshold below which loot is "negligible" when combined with any loss — drives RED. */
    private static final long RED_LOW_LOOT_THRESHOLD = 5_000L;
    /** Consecutive losses that force a prune-candidate flag and RED light. */
    private static final int RED_CONSECUTIVE_LOSSES = 3;
    /** Loss frequency that drives YELLOW. */
    private static final double YELLOW_LOSS_RATIO = 0.25;
    /** Minimum raids before we'll judge a target as "thin" on average loot. */
    private static final int YELLOW_MIN_RAIDS_FOR_THIN = 5;
    /** Average loot below this with enough raids drives YELLOW (too thin to bother). */
    private static final long YELLOW_THIN_AVG_LOOT = 200L;

    private final FarmlistTargetMetricRepository metricRepository;
    private final RaidReportRepository raidReportRepository;
    private final FarmlistTargetRepository farmlistTargetRepository;

    public FarmlistAnalyzerService(
            FarmlistTargetMetricRepository metricRepository,
            RaidReportRepository raidReportRepository,
            FarmlistTargetRepository farmlistTargetRepository
    ) {
        this.metricRepository = metricRepository;
        this.raidReportRepository = raidReportRepository;
        this.farmlistTargetRepository = farmlistTargetRepository;
    }

    /**
     * Apply a single raid report to its target's rollup. No-op when the
     * report wasn't attributed to a known target (one-off raids that don't
     * map back to a farmlist row).
     */
    public void ingest(RaidReport report) {
        if (report == null || report.getFarmlistTargetId() == null) {
            log.debug("Skipping ingest — report {} has no farmlistTargetId",
                    report == null ? null : report.getId());
            return;
        }
        FarmlistTargetMetric m = getOrCreate(report.getFarmlistTargetId());

        m.setTotalRaids(safe(m.getTotalRaids()) + 1);
        m.setTotalResourcesLooted(safe(m.getTotalResourcesLooted()) + report.totalLooted());
        if (report.getCarryCapacityTotal() != null) {
            m.setTotalCarryCapacityUsed(
                    safe(m.getTotalCarryCapacityUsed()) + report.getCarryCapacityTotal());
        }
        m.setTotalOurTroopsLost(safe(m.getTotalOurTroopsLost()) + safe(report.getOurTroopsLost()));
        m.setLastRaidAt(report.getRaidReturnedAt());
        if (Boolean.TRUE.equals(report.getWasFullHaul())) {
            m.setLastFullHaulAt(report.getRaidReturnedAt());
        }

        boolean isLossRaid = "DEFEAT".equals(report.getOutcome())
                || safe(report.getOurTroopsLost()) > 0;
        if (isLossRaid) {
            m.setRaidsWithLosses(safe(m.getRaidsWithLosses()) + 1);
            m.setConsecutiveLossRaids(safe(m.getConsecutiveLossRaids()) + 1);
        } else {
            m.setConsecutiveLossRaids(0);
        }

        recomputeTrafficLightAndPrune(m);
        m.touch();
        metricRepository.save(m);
    }

    /**
     * Loads or creates the metric row for a target. Public for tests; the
     * normal call path goes through {@link #ingest(RaidReport)}.
     */
    public FarmlistTargetMetric getOrCreate(Long farmlistTargetId) {
        return metricRepository.findFirstByFarmlistTargetId(farmlistTargetId)
                .orElseGet(() -> metricRepository.save(new FarmlistTargetMetric(farmlistTargetId)));
    }

    /**
     * Batch lookup keyed by farmlistTargetId. The UI uses this to render the
     * per-target table without an N+1 query.
     */
    @Transactional(readOnly = true)
    public Map<Long, FarmlistTargetMetric> metricsForTargets(Collection<Long> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) return Collections.emptyMap();
        Map<Long, FarmlistTargetMetric> out = new HashMap<>();
        for (FarmlistTargetMetric m : metricRepository.findAllByFarmlistTargetIdIn(targetIds)) {
            out.put(m.getFarmlistTargetId(), m);
        }
        return out;
    }

    /**
     * Returns the target ids in this farmlist that are currently flagged as
     * prune-candidates. Used by the UI to surface a red banner.
     */
    @Transactional(readOnly = true)
    public List<Long> listPruneCandidates(Long farmlistId) {
        List<FarmlistTarget> targets = farmlistTargetRepository.findAllByFarmlistId(farmlistId);
        if (targets.isEmpty()) return List.of();
        List<Long> ids = new ArrayList<>(targets.size());
        for (FarmlistTarget t : targets) ids.add(t.getId());

        List<Long> out = new ArrayList<>();
        for (FarmlistTargetMetric m : metricRepository.findAllByFarmlistTargetIdIn(ids)) {
            if (Boolean.TRUE.equals(m.getPrunCandidate())) {
                out.add(m.getFarmlistTargetId());
            }
        }
        return out;
    }

    /** Convenience for tests / debugging — list reports attributed to a target. */
    @Transactional(readOnly = true)
    public List<RaidReport> reportsForTarget(Long farmlistTargetId) {
        return raidReportRepository.findAllByFarmlistTargetIdOrderByRaidReturnedAtDesc(farmlistTargetId);
    }

    /**
     * Recompute the traffic light + prune flag from current totals. See the
     * {@link FarmlistTargetMetric} class javadoc for the rule definitions.
     */
    private static void recomputeTrafficLightAndPrune(FarmlistTargetMetric m) {
        int totalRaids = safe(m.getTotalRaids());
        int raidsWithLosses = safe(m.getRaidsWithLosses());
        int consecutiveLosses = safe(m.getConsecutiveLossRaids());
        long totalLoot = safe(m.getTotalResourcesLooted());
        int troopsLost = safe(m.getTotalOurTroopsLost());

        String light;
        if (consecutiveLosses >= RED_CONSECUTIVE_LOSSES
                || (troopsLost > 0 && totalLoot < RED_LOW_LOOT_THRESHOLD)) {
            light = "RED";
        } else {
            double lossRatio = (totalRaids == 0) ? 0.0 : (double) raidsWithLosses / totalRaids;
            double avgLoot = (totalRaids == 0) ? Double.MAX_VALUE : (double) totalLoot / totalRaids;
            boolean thin = totalRaids >= YELLOW_MIN_RAIDS_FOR_THIN && avgLoot < YELLOW_THIN_AVG_LOOT;
            if (lossRatio > YELLOW_LOSS_RATIO || thin) {
                light = "YELLOW";
            } else {
                light = "GREEN";
            }
        }
        m.setTrafficLight(light);
        m.setPrunCandidate("RED".equals(light) && consecutiveLosses >= RED_CONSECUTIVE_LOSSES);
    }

    private static int safe(Integer v) { return v == null ? 0 : v; }
    private static long safe(Long v) { return v == null ? 0L : v; }

}
