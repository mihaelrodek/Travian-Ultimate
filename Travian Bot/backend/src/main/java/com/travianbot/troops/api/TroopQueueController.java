package com.travianbot.troops.api;

import com.travianbot.troops.api.dto.TroopQueueItemDto;
import com.travianbot.troops.service.TroopQueueService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-village troop queue endpoints.
 * <p>
 * The queue is village-scoped because training is village-local in the game
 * (each village has its own barracks/stable/workshop). The URL reflects that:
 * {@code /api/villages/{villageId}/troop-queue}.
 * <p>
 * Reads use GET, full-replace writes use PUT — matches how the building queue
 * is edited (client sends the entire ordered list, server reconciles by id).
 */
@RestController
@RequestMapping("/api/villages/{villageId}/troop-queue")
public class TroopQueueController {

    private final TroopQueueService service;

    public TroopQueueController(TroopQueueService service) {
        this.service = service;
    }

    public record ReplaceRequest(@NotNull @Valid List<@Valid TroopQueueItemDto> items) {}

    @GetMapping
    public List<TroopQueueItemDto> list(@PathVariable Long villageId) {
        return service.listForVillage(villageId);
    }

    @PutMapping
    public List<TroopQueueItemDto> replace(
            @PathVariable Long villageId,
            @Valid @RequestBody ReplaceRequest request
    ) {
        return service.replaceQueue(villageId, request.items());
    }
}
