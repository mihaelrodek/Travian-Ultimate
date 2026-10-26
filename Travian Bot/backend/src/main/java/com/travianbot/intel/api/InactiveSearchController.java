package com.travianbot.intel.api;

import com.travianbot.intel.api.dto.InactivePlayerDetailResponse;
import com.travianbot.intel.api.dto.InactivePlayerPageResponse;
import com.travianbot.intel.api.dto.VillagePopulationPointResponse;
import com.travianbot.intel.domain.MapVillagePopulationSnapshot;
import com.travianbot.intel.service.InactiveSearchService;
import com.travianbot.intel.service.InactiveSearchService.InactiveSearchFilters;
import com.travianbot.intel.service.InactiveSearchService.PlayerDetail;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * REST surface for the inactive-search / intel module. Now server-scoped — any
 * Travian world the user has added (via {@code /api/servers}) can be searched,
 * regardless of which accounts they own on it.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/intel")
public class InactiveSearchController {

    /** Hard cap so a rogue client can't ask for thousands of rows. */
    private static final int MAX_PAGE_SIZE = 200;

    private final InactiveSearchService service;

    public InactiveSearchController(InactiveSearchService service) {
        this.service = service;
    }

    /** Trigger an immediate map.sql sync for this server. */
    @PostMapping("/sync")
    public SyncResponse sync(@PathVariable Long serverId) {
        int upserts = service.syncForServer(serverId);
        return new SyncResponse(upserts);
    }

    /** Filtered, paginated list of tracked players. */
    @GetMapping("/inactives")
    public InactivePlayerPageResponse search(
            @PathVariable Long serverId,
            @RequestParam(required = false) Integer minIdleDays,
            @RequestParam(required = false) Integer maxPopulation,
            @RequestParam(required = false) Integer minPopulation,
            @RequestParam(required = false) Integer maxPopulationIncrease,
            @RequestParam(required = false) Integer minVillageCount,
            @RequestParam(required = false) Integer maxVillageCount,
            @RequestParam(required = false) Integer xMin,
            @RequestParam(required = false) Integer xMax,
            @RequestParam(required = false) Integer yMin,
            @RequestParam(required = false) Integer yMax,
            @RequestParam(required = false) Long nearVillageId,
            @RequestParam(required = false) Double maxDistance,
            @RequestParam(required = false) String tribeIds,
            @RequestParam(required = false) String allianceExclude,
            @RequestParam(required = false) String allianceInclude,
            @RequestParam(required = false) Long allianceTravianId,
            @RequestParam(required = false) Integer lastSeenWithinDays,
            @RequestParam(required = false) Boolean inactiveOnly,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(safePage, safeSize);

        List<Integer> tribeList = parseTribeCsv(tribeIds);
        InactiveSearchFilters filters = new InactiveSearchFilters(
                serverId,
                minIdleDays,
                maxPopulation,
                minPopulation,
                maxPopulationIncrease,
                minVillageCount,
                maxVillageCount,
                xMin, xMax, yMin, yMax,
                nearVillageId,
                maxDistance,
                tribeList,
                allianceExclude,
                allianceInclude,
                allianceTravianId,
                lastSeenWithinDays,
                inactiveOnly,
                sortBy,
                sortDir
        );
        return InactivePlayerPageResponse.from(service.search(serverId, filters, pageable));
    }

    /**
     * Per-village population history — newest-first, capped at {@code points}.
     * Used by the sparkline in the player detail modal. The chart shows
     * change-only snapshots; villages that haven't grown since their last
     * recorded snapshot won't have a new point this sync.
     */
    @GetMapping("/villages/{mapVillageId}/population-history")
    public List<VillagePopulationPointResponse> populationHistory(
            @PathVariable Long serverId,
            @PathVariable Long mapVillageId,
            @RequestParam(required = false, defaultValue = "30") int points) {
        List<MapVillagePopulationSnapshot> rows = service.populationHistory(mapVillageId, points);
        return rows.stream()
                .map(s -> new VillagePopulationPointResponse(s.getCapturedAt(), s.getPopulation()))
                .toList();
    }

    /** Detail view for a single player by Travian uid. */
    @GetMapping("/players/{uid}")
    public ResponseEntity<InactivePlayerDetailResponse> getPlayer(
            @PathVariable Long serverId,
            @PathVariable Long uid
    ) {
        Optional<PlayerDetail> detail = service.getPlayer(serverId, uid);
        return detail.map(d -> ResponseEntity.ok(InactivePlayerDetailResponse.from(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Parse a comma-separated tribe id list like {@code "1,2,3"}. Empty / null
     * input means "don't filter on tribe". Non-integer tokens are silently
     * dropped — being lenient with a query param costs nothing and saves the
     * frontend from sending precisely-validated input.
     */
    private static List<Integer> parseTribeCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
                })
                .filter(n -> n != null)
                .toList();
    }

    /** Tiny envelope so the sync response stays a JSON object (not a bare integer). */
    public record SyncResponse(int playersSynced) {}
}
