package com.travianbot.village.api;

import com.travianbot.bot.scraper.ScrapingService;
import com.travianbot.village.api.dto.QueueUpdateRequest;
import com.travianbot.village.api.dto.VillageRequest;
import com.travianbot.village.api.dto.VillageResponse;
import com.travianbot.village.service.VillageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/villages")
public class VillageController {

    private final VillageService service;
    private final ScrapingService scrapingService;

    public VillageController(VillageService service, ScrapingService scrapingService) {
        this.service = service;
        this.scrapingService = scrapingService;
    }

    /** Optional accountId filter — no filter returns all villages across all accounts. */
    @GetMapping
    public List<VillageResponse> list(@RequestParam(required = false) Long accountId) {
        return service.listDto(accountId);
    }

    @GetMapping("/{id}")
    public VillageResponse get(@PathVariable Long id) {
        return service.getDto(id);
    }

    @PostMapping
    public ResponseEntity<VillageResponse> create(@Valid @RequestBody VillageRequest request) {
        VillageResponse body = service.createDto(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public VillageResponse update(@PathVariable Long id, @Valid @RequestBody VillageRequest request) {
        return service.updateDto(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/queue")
    public VillageResponse replaceQueue(@PathVariable Long id, @Valid @RequestBody QueueUpdateRequest request) {
        return service.replaceQueueDto(id, request.items());
    }

    /**
     * Persists a user-chosen ordering for the Villages sidebar. The incoming
     * body is the authoritative list of village ids in the new order —
     * position 0 becomes orderIndex 0, etc. Cross-account ids are silently
     * ignored so the call is safe when the frontend race-reads an old list.
     * Responds with the full re-ordered account list so the UI can skip a
     * round-trip refresh.
     */
    @PostMapping("/reorder")
    public List<VillageResponse> reorder(
            @RequestParam Long accountId,
            @RequestBody List<Long> villageIds
    ) {
        return service.reorderDto(accountId, villageIds);
    }

    /**
     * Start the in-game upgrade for one queued item. Requires the owning
     * account to have an open, logged-in browser session — returns 409 via
     * {@code NotLoggedInException} otherwise.
     */
    @PostMapping("/{id}/queue/{queueItemId}/start")
    public VillageResponse startQueueItem(@PathVariable Long id, @PathVariable Long queueItemId) {
        return service.startQueueItem(id, queueItemId);
    }

    /**
     * Scrape the given village from the live game. Requires the account to have an open
     * browser session (login first). Returns the refreshed village with resource fields
     * and buildings populated.
     */
    @PostMapping("/{id}/scrape")
    public VillageResponse scrape(@PathVariable Long id) {
        var village = scrapingService.scrapeVillage(
                service.get(id).getAccount().getId(),
                id
        );
        return service.getDto(village.getId());
    }
}
