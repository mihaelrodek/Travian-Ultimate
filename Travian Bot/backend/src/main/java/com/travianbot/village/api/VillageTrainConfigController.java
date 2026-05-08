package com.travianbot.village.api;

import com.travianbot.village.api.dto.VillageTrainConfigDtos.TrainConfigRequest;
import com.travianbot.village.api.dto.VillageTrainConfigDtos.TrainConfigResponse;
import com.travianbot.village.service.VillageTrainConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read / replace the per-village troop training config. PUT semantics are
 * "the request is the truth" — same shape as the build-queue editor — so the
 * frontend can drop a row by omitting it from the body.
 */
@RestController
@RequestMapping("/api/villages/{villageId}/train-config")
public class VillageTrainConfigController {

    private final VillageTrainConfigService service;

    public VillageTrainConfigController(VillageTrainConfigService service) {
        this.service = service;
    }

    @GetMapping
    public TrainConfigResponse get(@PathVariable Long villageId) {
        return service.list(villageId);
    }

    @PutMapping
    public TrainConfigResponse put(@PathVariable Long villageId,
                                   @Valid @RequestBody TrainConfigRequest req) {
        return service.replace(villageId, req);
    }
}
