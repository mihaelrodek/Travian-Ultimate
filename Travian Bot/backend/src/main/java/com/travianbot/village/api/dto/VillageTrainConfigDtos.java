package com.travianbot.village.api.dto;

import com.travianbot.village.domain.VillageTrainConfig;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * DTOs for the per-village troop training configuration. Replace-all on PUT
 * — same shape as the build-queue editor — so the UI can drop a row by just
 * not sending it.
 */
public final class VillageTrainConfigDtos {

    private VillageTrainConfigDtos() {
    }

    public record TrainConfigItem(
            @NotBlank String troopCode,
            @Min(0) int minQty,
            @Min(0) int maxQty
    ) {
        public static TrainConfigItem from(VillageTrainConfig c) {
            return new TrainConfigItem(c.getTroopCode(), c.getMinQty(), c.getMaxQty());
        }
    }

    public record TrainConfigRequest(List<TrainConfigItem> items) {
    }

    public record TrainConfigResponse(Long villageId, List<TrainConfigItem> items) {
    }
}
