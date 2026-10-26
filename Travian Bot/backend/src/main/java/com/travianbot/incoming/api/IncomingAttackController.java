package com.travianbot.incoming.api;

import com.travianbot.incoming.api.dto.IncomingAttackResponse;
import com.travianbot.incoming.api.dto.ReturnCatchSuggestionResponse;
import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.service.IncomingAttackService;
import com.travianbot.incoming.service.ReturnCatchPlanner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP endpoints for the incoming-attacks feature.
 * <ul>
 *   <li>{@code GET /api/accounts/{accountId}/incoming} — every recorded
 *       incoming for the account, soonest arrival first.</li>
 *   <li>{@code POST /api/accounts/{accountId}/incoming/scrape} — manual scan
 *       (the scheduler runs every 90s automatically). Returns a tiny envelope
 *       with the number of NEW waves recorded so the UI can confirm a fresh
 *       click did something.</li>
 *   <li>{@code POST /api/accounts/{accountId}/incoming/{id}/acknowledge} —
 *       flip the read flag.</li>
 *   <li>{@code GET /api/accounts/{accountId}/incoming/return-catch} — the
 *       planner's suggestions for sniping the attacker's home village.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/incoming")
public class IncomingAttackController {

    private final IncomingAttackService service;
    private final ReturnCatchPlanner planner;

    public IncomingAttackController(IncomingAttackService service, ReturnCatchPlanner planner) {
        this.service = service;
        this.planner = planner;
    }

    @GetMapping
    public List<IncomingAttackResponse> list(@PathVariable Long accountId) {
        List<IncomingAttack> rows = service.listFor(accountId);
        return rows.stream().map(IncomingAttackResponse::from).toList();
    }

    @PostMapping("/scrape")
    public Map<String, Integer> scrape(@PathVariable Long accountId) {
        int scraped = service.scrapeForAccount(accountId);
        return Map.of("scraped", scraped);
    }

    /**
     * Manual paste-in import. Body shape: {@code { "text": "<pasted content>" }}.
     * Accepts either rally-point HTML (view-source paste) or plain text
     * (Ctrl+C from the rendered page) — the parser detects which.
     */
    @PostMapping("/import-text")
    public Map<String, Integer> importText(
            @PathVariable Long accountId,
            @RequestBody ImportTextRequest body) {
        int created = service.importPastedText(accountId, body == null ? null : body.text());
        return Map.of("imported", created);
    }

    /** Tiny request envelope for {@link #importText}. */
    public record ImportTextRequest(String text) {}

    @PostMapping("/{id}/acknowledge")
    public IncomingAttackResponse acknowledge(
            @PathVariable Long accountId,
            @PathVariable Long id
    ) {
        return IncomingAttackResponse.from(service.acknowledge(id));
    }

    /**
     * Set the manual speed-artifact multiplier on one attack row and re-run
     * the TS solver against it. Body shape:
     * {@code { "artifactMultiplier": 1.5 }} — or {@code { "artifactMultiplier": null }}
     * to clear the override. The seven legal in-game values are accepted
     * verbatim; anything else is coerced to 1.0 by the analyzer.
     */
    @PostMapping("/{id}/artifact")
    public IncomingAttackResponse setArtifact(
            @PathVariable Long accountId,
            @PathVariable Long id,
            @RequestBody SetArtifactRequest body
    ) {
        Double multiplier = body == null ? null : body.artifactMultiplier();
        return IncomingAttackResponse.from(service.setManualArtifactMultiplier(id, multiplier));
    }

    /** Tiny request envelope for {@link #setArtifact}. */
    public record SetArtifactRequest(Double artifactMultiplier) {}

    /**
     * Re-run the Tournament-Square inverse solver against every persisted
     * {@link IncomingAttack} row for the account. Useful after:
     * <ul>
     *   <li>The user fills in {@code Server.speedMultiplier} (legacy rows)</li>
     *   <li>The troop-speed catalog changes</li>
     * </ul>
     * Returns the number of rows touched. Doesn't fetch anything from
     * Travian — purely a local recompute against existing data.
     */
    @PostMapping("/reanalyse")
    public Map<String, Integer> reanalyse(@PathVariable Long accountId) {
        int touched = service.reanalyseForAccount(accountId);
        return Map.of("reanalysed", touched);
    }

    /**
     * Wipe every incoming-attack row for the account. Use after a parser fix
     * lands and previously-stored rows have garbage coords / distances — the
     * UI calls this then re-imports cleanly.
     */
    @PostMapping("/clear")
    public Map<String, Integer> clear(@PathVariable Long accountId) {
        int removed = service.clearAllFor(accountId);
        return Map.of("removed", removed);
    }

    @GetMapping("/return-catch")
    public List<ReturnCatchSuggestionResponse> returnCatch(@PathVariable Long accountId) {
        return planner.suggestFor(accountId).stream()
                .map(ReturnCatchSuggestionResponse::from)
                .toList();
    }
}
