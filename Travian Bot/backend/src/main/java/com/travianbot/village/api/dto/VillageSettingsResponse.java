package com.travianbot.village.api.dto;

import com.travianbot.village.domain.VillageSettings;

import java.time.Instant;

/**
 * Village settings + a copy of {@code lastScrapedAt} from the Village row so
 * the Settings tab can render "Last scraped …" without a separate GET on
 * /api/villages/{id}. The timestamp is read-only — the scraper owns it.
 */
public record VillageSettingsResponse(
        Long villageId,

        boolean npcEnabled,
        int npcTriggerPct,
        int npcRatioWood,
        int npcRatioClay,
        int npcRatioIron,
        int npcRatioCrop,
        int goldReserve,

        boolean autoScrapeEnabled,
        int autoScrapeIntervalMinutes,
        Instant lastScrapedAt,

        boolean cropWatcherEnabled,
        int cropWatcherThresholdPct,
        int cropWatcherMinGold,

        boolean trainTroopsEnabled,
        int trainTroopsMinMinutes,
        int trainTroopsMaxMinutes
) {
    public static VillageSettingsResponse from(VillageSettings s) {
        return new VillageSettingsResponse(
                s.getVillage().getId(),

                s.isNpcEnabled(),
                s.getNpcTriggerPct(),
                s.getNpcRatioWood(),
                s.getNpcRatioClay(),
                s.getNpcRatioIron(),
                s.getNpcRatioCrop(),
                s.getGoldReserve(),

                s.isAutoScrapeEnabled(),
                s.getAutoScrapeIntervalMinutes(),
                s.getVillage().getLastScrapedAt(),

                s.isCropWatcherEnabled(),
                s.getCropWatcherThresholdPct(),
                s.getCropWatcherMinGold(),

                s.isTrainTroopsEnabled(),
                s.getTrainTroopsMinMinutes(),
                s.getTrainTroopsMaxMinutes()
        );
    }
}
