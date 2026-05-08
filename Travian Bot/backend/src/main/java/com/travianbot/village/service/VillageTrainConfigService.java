package com.travianbot.village.service;

import com.travianbot.village.api.dto.VillageTrainConfigDtos.TrainConfigItem;
import com.travianbot.village.api.dto.VillageTrainConfigDtos.TrainConfigRequest;
import com.travianbot.village.api.dto.VillageTrainConfigDtos.TrainConfigResponse;
import com.travianbot.village.domain.Village;
import com.travianbot.village.domain.VillageTrainConfig;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.persistence.VillageTrainConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read / replace the per-village training configuration. PUT semantics are
 * "the request is the truth" — rows missing from the incoming list are
 * deleted, new rows are inserted, existing rows are updated in place. That
 * mirrors how {@code BuildingQueueItemService.replaceQueue} behaves so the
 * UI doesn't need separate add / remove endpoints for what is naturally a
 * "save the whole form" interaction.
 * <p>
 * Rows where both {@code minQty} and {@code maxQty} are zero are discarded
 * — they're the UI's way of saying "I don't want to train this" and there's
 * no point persisting them.
 */
@Service
@Transactional
public class VillageTrainConfigService {

    private final VillageTrainConfigRepository repository;
    private final VillageRepository villageRepository;

    public VillageTrainConfigService(VillageTrainConfigRepository repository,
                                     VillageRepository villageRepository) {
        this.repository = repository;
        this.villageRepository = villageRepository;
    }

    @Transactional(readOnly = true)
    public TrainConfigResponse list(Long villageId) {
        if (!villageRepository.existsById(villageId)) {
            throw new VillageNotFoundException(villageId);
        }
        List<TrainConfigItem> items = repository
                .findAllByVillageIdOrderByTroopCodeAsc(villageId).stream()
                .map(TrainConfigItem::from)
                .toList();
        return new TrainConfigResponse(villageId, items);
    }

    public TrainConfigResponse replace(Long villageId, TrainConfigRequest req) {
        Village village = villageRepository.findById(villageId)
                .orElseThrow(() -> new VillageNotFoundException(villageId));

        List<VillageTrainConfig> existing = repository.findAllByVillageIdOrderByTroopCodeAsc(villageId);
        Map<String, VillageTrainConfig> byCode = new HashMap<>();
        for (VillageTrainConfig c : existing) byCode.put(c.getTroopCode(), c);

        List<VillageTrainConfig> toSave = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (req.items() != null) {
            for (TrainConfigItem item : req.items()) {
                String code = item.troopCode().trim();
                if (code.isEmpty()) continue;
                int min = Math.max(0, item.minQty());
                int max = Math.max(0, item.maxQty());
                // "0/0" means the row is meaningful only as UI state — don't
                // persist a row that says "train zero". The same pattern is
                // why the build queue editor doesn't store empty rows.
                if (min == 0 && max == 0) continue;
                // Auto-swap reversed bounds to keep the scheduler safe.
                int lo = Math.min(min, max);
                int hi = Math.max(min, max);
                if (seen.contains(code)) continue; // de-dupe — last writer wins, but we already kept the first
                seen.add(code);
                VillageTrainConfig row = byCode.get(code);
                if (row != null) {
                    row.update(lo, hi);
                    toSave.add(row);
                } else {
                    toSave.add(new VillageTrainConfig(village, code, lo, hi));
                }
            }
        }
        // Delete the rows that fell out of the request.
        for (VillageTrainConfig row : existing) {
            if (!seen.contains(row.getTroopCode())) {
                repository.delete(row);
            }
        }
        repository.saveAll(toSave);
        return list(villageId);
    }

    /**
     * Cascade hook used by {@code VillageService.delete}. Kept on this
     * service so the village-delete path doesn't need to know the entity
     * shape directly.
     */
    public void deleteByVillageId(Long villageId) {
        repository.deleteByVillageId(villageId);
    }
}
