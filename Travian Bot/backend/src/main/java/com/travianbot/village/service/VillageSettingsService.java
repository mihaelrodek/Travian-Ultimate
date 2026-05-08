package com.travianbot.village.service;

import com.travianbot.village.api.dto.VillageSettingsRequest;
import com.travianbot.village.api.dto.VillageSettingsResponse;
import com.travianbot.village.domain.Village;
import com.travianbot.village.domain.VillageSettings;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.persistence.VillageSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads/writes per-village settings. Auto-creates a defaults row on first read
 * so every village always has settings, even those created before this feature
 * existed (the schema-upgrade backfill handles the same case at the DDL layer;
 * this is the defence against any row that slipped through).
 * <p>
 * All mapping to DTOs happens inside {@code @Transactional} methods because
 * {@code spring.jpa.open-in-view=false} — if we handed the entity back to the
 * controller the {@code village} relation would throw a
 * LazyInitializationException when the DTO mapper touched it.
 */
@Service
@Transactional
public class VillageSettingsService {

    private final VillageSettingsRepository repository;
    private final VillageRepository villageRepository;

    public VillageSettingsService(VillageSettingsRepository repository, VillageRepository villageRepository) {
        this.repository = repository;
        this.villageRepository = villageRepository;
    }

    /**
     * Creates a defaults row for a brand new village. Called by
     * {@code VillageService} right after persisting the village — keeps
     * {@code getForVillage} guaranteed-non-null for the life of the village.
     */
    public VillageSettings createDefaults(Village village) {
        return repository.save(new VillageSettings(village));
    }

    /**
     * Returns settings, auto-creating if missing. Infallible as long as the
     * village exists.
     */
    public VillageSettings getForVillage(Long villageId) {
        return repository.findByVillageId(villageId)
                .orElseGet(() -> {
                    Village v = villageRepository.findById(villageId)
                            .orElseThrow(() -> new VillageNotFoundException(villageId));
                    return createDefaults(v);
                });
    }

    public VillageSettingsResponse getForVillageDto(Long villageId) {
        return VillageSettingsResponse.from(getForVillage(villageId));
    }

    public VillageSettingsResponse updateDto(Long villageId, VillageSettingsRequest req) {
        return VillageSettingsResponse.from(update(villageId, req));
    }

    public VillageSettings update(Long villageId, VillageSettingsRequest req) {
        validateNpcRatios(req);

        VillageSettings s = getForVillage(villageId);

        s.setNpcEnabled(req.npcEnabled());
        s.setNpcTriggerPct(req.npcTriggerPct());
        s.setNpcRatioWood(req.npcRatioWood());
        s.setNpcRatioClay(req.npcRatioClay());
        s.setNpcRatioIron(req.npcRatioIron());
        s.setNpcRatioCrop(req.npcRatioCrop());
        s.setGoldReserve(req.goldReserve());

        s.setAutoScrapeEnabled(req.autoScrapeEnabled());
        s.setAutoScrapeIntervalMinutes(req.autoScrapeIntervalMinutes());

        s.setCropWatcherEnabled(req.cropWatcherEnabled());
        s.setCropWatcherThresholdPct(req.cropWatcherThresholdPct());
        s.setCropWatcherMinGold(req.cropWatcherMinGold());

        // Min/max sanity: swap if the user typed them the wrong way round —
        // a UI guard would also catch this but accepting either order keeps
        // the settings PUT idempotent.
        int minMin = Math.min(req.trainTroopsMinMinutes(), req.trainTroopsMaxMinutes());
        int maxMin = Math.max(req.trainTroopsMinMinutes(), req.trainTroopsMaxMinutes());
        s.setTrainTroopsEnabled(req.trainTroopsEnabled());
        s.setTrainTroopsMinMinutes(minMin);
        s.setTrainTroopsMaxMinutes(maxMin);

        return repository.save(s);
    }

    /**
     * Removes the settings row for a village. Must be called before the
     * parent {@link Village} is deleted (the FK is non-null so an orphan here
     * would block the delete).
     */
    public void deleteByVillageId(Long villageId) {
        repository.deleteByVillageId(villageId);
    }

    private void validateNpcRatios(VillageSettingsRequest req) {
        // Only enforce the sum-to-100 rule when NPC is actually enabled —
        // otherwise saving a draft with 0/0/0/0 would be impossible.
        if (!req.npcEnabled()) return;
        int sum = req.npcRatioWood() + req.npcRatioClay() + req.npcRatioIron() + req.npcRatioCrop();
        if (sum != 100) {
            throw new IllegalArgumentException("NPC ratios must sum to 100 (got " + sum + ")");
        }
    }
}
