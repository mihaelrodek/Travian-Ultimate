package com.travianbot.village.api;

import com.travianbot.village.api.dto.VillageSettingsRequest;
import com.travianbot.village.api.dto.VillageSettingsResponse;
import com.travianbot.village.service.VillageSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-village settings. Kept on its own path ({@code /api/villages/{id}/settings})
 * rather than bundled into {@code VillageResponse} because the village list
 * render shouldn't have to load NPC ratios for 50+ villages on each tab switch.
 */
@RestController
@RequestMapping("/api/villages/{villageId}/settings")
public class VillageSettingsController {

    private final VillageSettingsService service;

    public VillageSettingsController(VillageSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public VillageSettingsResponse get(@PathVariable Long villageId) {
        return service.getForVillageDto(villageId);
    }

    @PutMapping
    public VillageSettingsResponse update(
            @PathVariable Long villageId,
            @Valid @RequestBody VillageSettingsRequest request
    ) {
        return service.updateDto(villageId, request);
    }
}
