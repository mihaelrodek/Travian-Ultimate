package com.travianbot.server.api;

import com.travianbot.server.api.dto.DiscoveryReportResponse;
import com.travianbot.server.discovery.CandidateUrlGenerator;
import com.travianbot.server.discovery.DiscoveryReport;
import com.travianbot.server.discovery.ServerDiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * One-shot server-discovery surface.
 * <p>
 * Sits alongside the existing {@link ServerController} as a sub-resource —
 * mounted on {@code /api/servers/discovery} so the dedicated capability has a
 * clean URL space without shadowing the main server CRUD.
 * <p>
 * The {@code POST /} probe is <b>synchronous and blocking</b>: it fans out
 * ~600-1600 HTTP probes on a 16-thread pool and waits for the full set to
 * settle. Expected wall-clock is 30-60 s. This is fine for a manual,
 * user-initiated trigger from the UI — the frontend shows a busy state — but
 * the endpoint should NOT be called from automated tooling without an
 * appropriate client-side read timeout.
 */
@RestController
@RequestMapping("/api/servers/discovery")
public class ServerDiscoveryController {

    private final ServerDiscoveryService discoveryService;
    private final CandidateUrlGenerator candidateUrlGenerator;

    public ServerDiscoveryController(ServerDiscoveryService discoveryService,
                                     CandidateUrlGenerator candidateUrlGenerator) {
        this.discoveryService = discoveryService;
        this.candidateUrlGenerator = candidateUrlGenerator;
    }

    /**
     * Trigger a fresh discovery run. Blocks until every candidate has been
     * probed or timed out. New servers are persisted as {@code active=false} —
     * the user enables what they want from the server picker.
     */
    @PostMapping
    public DiscoveryReportResponse discover() {
        DiscoveryReport report = discoveryService.discoverAll();
        return DiscoveryReportResponse.from(report);
    }

    /**
     * Debug helper — returns the full deterministic candidate list. Useful for
     * the UI to preview "what will I probe?" without actually probing, and
     * for diff'ing the list across runs after the constants are tweaked.
     */
    @GetMapping("/candidates")
    public List<String> candidates() {
        return candidateUrlGenerator.generateAll();
    }
}
