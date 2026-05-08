package com.travianbot.aianalysis.api;

import com.travianbot.aianalysis.api.dto.OasisScanDtos.OasisScanResponse;
import com.travianbot.aianalysis.api.dto.OasisScanDtos.StartScanRequest;
import com.travianbot.aianalysis.domain.OasisScan;
import com.travianbot.aianalysis.service.OasisAnalysisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * REST surface for the AI Analysis (oasis scan) feature.
 * <ul>
 *   <li>{@code GET /api/aianalysis/{accountId}/latest} — most recent scan
 *       for an account, or 404 when none exists yet.</li>
 *   <li>{@code GET /api/aianalysis/{accountId}/history} — every scan, newest
 *       first. Returns the scan rows without their tile children to keep the
 *       payload light; the dashboard fetches tiles on demand via the
 *       latest endpoint.</li>
 *   <li>{@code POST /api/aianalysis/{accountId}/scan} — kick off a new scan.
 *       Returns the new scan + its tiles in one shot.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/aianalysis")
public class OasisAnalysisController {

    private final OasisAnalysisService service;

    public OasisAnalysisController(OasisAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/{accountId}/latest")
    public ResponseEntity<OasisScanResponse> latest(@PathVariable Long accountId) {
        Optional<OasisScan> scan = service.latestForAccount(accountId);
        return scan.map(s -> ResponseEntity.ok(OasisScanResponse.from(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{accountId}/history")
    public List<OasisScanResponse> history(@PathVariable Long accountId) {
        // Project the history without its tile children to keep this list
        // cheap. Callers wanting tiles for a specific scan should use latest /
        // the dedicated by-id endpoint (future).
        return service.historyForAccount(accountId).stream()
                .map(OasisScanResponse::from)
                .toList();
    }

    @PostMapping("/{accountId}/scan")
    public OasisScanResponse runScan(@PathVariable Long accountId,
                                     @Valid @RequestBody StartScanRequest req) {
        return OasisScanResponse.from(
                service.runScan(accountId, req.centerX(), req.centerY(), req.radius())
        );
    }
}
