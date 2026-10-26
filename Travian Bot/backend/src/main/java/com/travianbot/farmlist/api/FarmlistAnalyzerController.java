package com.travianbot.farmlist.api;

import com.travianbot.farmlist.api.dto.FarmlistTargetMetricResponse;
import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.domain.FarmlistTargetMetric;
import com.travianbot.farmlist.persistence.FarmlistTargetRepository;
import com.travianbot.farmlist.service.FarmlistAnalyzerService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only endpoints for the FarmlistAnalyticsTab — surfaces the per-target
 * rollup and the prune-candidate list.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/farmlists/{farmlistId}/analytics")
public class FarmlistAnalyzerController {

    private final FarmlistAnalyzerService analyzerService;
    private final FarmlistTargetRepository farmlistTargetRepository;

    public FarmlistAnalyzerController(FarmlistAnalyzerService analyzerService,
                                      FarmlistTargetRepository farmlistTargetRepository) {
        this.analyzerService = analyzerService;
        this.farmlistTargetRepository = farmlistTargetRepository;
    }

    /**
     * Returns one row per target on the list — even targets without a metric
     * row come back with zero defaults so the UI can render the table.
     * Empty list is a valid response — a brand-new farmlist with no targets
     * is not an error.
     */
    @GetMapping("/metrics")
    @Transactional(readOnly = true)
    public List<FarmlistTargetMetricResponse> metrics(@PathVariable Long accountId,
                                                      @PathVariable Long farmlistId) {
        List<FarmlistTarget> targets = farmlistTargetRepository.findAllByFarmlistId(farmlistId);
        if (targets.isEmpty()) return List.of();

        List<Long> ids = new ArrayList<>(targets.size());
        for (FarmlistTarget t : targets) ids.add(t.getId());

        Map<Long, FarmlistTargetMetric> byTargetId = analyzerService.metricsForTargets(ids);
        List<FarmlistTargetMetricResponse> out = new ArrayList<>(targets.size());
        for (FarmlistTarget t : targets) {
            out.add(FarmlistTargetMetricResponse.from(t, byTargetId.get(t.getId())));
        }
        return out;
    }

    /**
     * Returns just the prune-candidate {@code farmlistTargetId}s. The
     * frontend uses this to drive the red banner without re-walking the
     * full metrics table.
     */
    @GetMapping("/prune-candidates")
    public List<Long> pruneCandidates(@PathVariable Long accountId,
                                      @PathVariable Long farmlistId) {
        return analyzerService.listPruneCandidates(farmlistId);
    }
}
