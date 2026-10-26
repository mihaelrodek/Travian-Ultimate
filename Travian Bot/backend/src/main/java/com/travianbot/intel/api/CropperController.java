package com.travianbot.intel.api;

import com.travianbot.intel.api.dto.CropperCandidateResponse;
import com.travianbot.intel.service.CropperClassifierService;
import com.travianbot.intel.service.CropperFinderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * REST surface for the cropper-finder feature. Sits alongside
 * {@link InactiveSearchController} under the same {@code /intel} prefix —
 * both consume the same {@link com.travianbot.intel.domain.MapVillage} table.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/intel/croppers")
public class CropperController {

    /** Default search radius if the caller doesn't override. */
    private static final double DEFAULT_MAX_DISTANCE = 30.0;

    /** Default classification filter — everything except UNKNOWN. */
    private static final String DEFAULT_CLASSIFICATIONS_CSV =
            CropperClassifierService.HIGH_15C + ","
                    + CropperClassifierService.LIKELY_15C + ","
                    + CropperClassifierService.POSSIBLE_15C + ","
                    + CropperClassifierService.LIKELY_9C;

    private final CropperFinderService finderService;
    private final CropperClassifierService classifierService;

    public CropperController(
            CropperFinderService finderService,
            CropperClassifierService classifierService
    ) {
        this.finderService = finderService;
        this.classifierService = classifierService;
    }

    /**
     * List likely-cropper villages near the chosen pivot, ranked by distance.
     * Filter via {@code classifications} CSV; default keeps every non-UNKNOWN
     * bucket. {@code includeOccupied=false} hides villages whose player still
     * looks active (a common workflow — "show me croppers I might pry loose").
     */
    @GetMapping
    public List<CropperCandidateResponse> findCroppers(
            @PathVariable Long serverId,
            @RequestParam Long nearVillageId,
            @RequestParam(required = false, defaultValue = "30.0") double maxDistance,
            @RequestParam(required = false) String classifications,
            @RequestParam(required = false, defaultValue = "true") boolean includeOccupied
    ) {
        double safeMax = Math.max(1.0, Math.min(maxDistance, 400.0));
        Set<String> wanted = parseClassificationsCsv(
                classifications == null ? DEFAULT_CLASSIFICATIONS_CSV : classifications);
        return finderService
                .findNearVillage(serverId, nearVillageId, safeMax, wanted, includeOccupied)
                .stream()
                .map(CropperCandidateResponse::from)
                .toList();
    }

    /**
     * Re-run the classifier across every {@link com.travianbot.intel.domain.MapVillage}
     * for this server. Useful after the heuristic itself is tweaked, or when
     * the daily sync hasn't fired yet but new villages were added through some
     * other path.
     */
    @PostMapping("/reclassify")
    public ReclassifyResponse reclassify(@PathVariable Long serverId) {
        int classified = classifierService.classifyServer(serverId);
        return new ReclassifyResponse(classified);
    }

    /**
     * Parse {@code "HIGH_15C,LIKELY_15C,..."} into a small set. We preserve
     * insertion order via {@link LinkedHashSet} — purely cosmetic but makes
     * debug logs read in the order the client sent.
     */
    private static Set<String> parseClassificationsCsv(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Tiny envelope so the reclassify response stays a JSON object. */
    public record ReclassifyResponse(int classified) {}
}
