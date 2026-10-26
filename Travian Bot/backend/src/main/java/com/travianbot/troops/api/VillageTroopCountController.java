package com.travianbot.troops.api;

import com.travianbot.troops.api.dto.VillageTroopCountResponse;
import com.travianbot.troops.service.VillageTroopCountService;
import com.travianbot.troops.service.VillageTroopCountService.ImportSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints for the per-village troop-count snapshots feature.
 * <ul>
 *   <li>{@code GET /api/accounts/{accountId}/troop-counts} — latest snapshot
 *       per village for the account.</li>
 *   <li>{@code POST /api/accounts/{accountId}/troop-counts/import-text} —
 *       paste-in import. Body: {@code { "text": "<paste>" }}. Returns the
 *       import summary {@code (imported, skipped, parsed, tribeId)}.</li>
 *   <li>{@code POST /api/accounts/{accountId}/troop-counts/clear} — wipe
 *       every snapshot for the account.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/troop-counts")
public class VillageTroopCountController {

    private final VillageTroopCountService service;

    public VillageTroopCountController(VillageTroopCountService service) {
        this.service = service;
    }

    @GetMapping
    public List<VillageTroopCountResponse> list(@PathVariable Long accountId) {
        return service.listFor(accountId).stream()
                .map(VillageTroopCountResponse::from)
                .toList();
    }

    @PostMapping("/import-text")
    public ImportSummary importText(
            @PathVariable Long accountId,
            @RequestBody ImportRequest body
    ) {
        return service.importPaste(accountId, body == null ? null : body.text());
    }

    @PostMapping("/clear")
    public Map<String, Integer> clear(@PathVariable Long accountId) {
        return Map.of("removed", service.clearFor(accountId));
    }

    /** Tiny request envelope. */
    public record ImportRequest(String text) {}
}
