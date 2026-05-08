package com.travianbot.village.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Full replace for a village's settings. PUT semantics like the account settings
 * DTO — send the whole object, the backend overwrites.
 * <p>
 * NPC ratios must sum to 100 when {@code npcEnabled} is true; the service
 * validates this so we can still accept drafts where the user has NPC off.
 * <p>
 * The {@code cropWatcher*} trio is the standalone "starvation rescue" trigger —
 * independent of the regular NPC rules so a village can run with NPC disabled
 * but still bail out before the granary runs dry.
 * <p>
 * The {@code trainTroops*} trio drives the per-village troop training
 * scheduler. Min/max bracket the random delay between consecutive cycles
 * (humanization). Per-troop quantities live in a separate
 * {@code village_train_config} table — this DTO carries only the village-wide
 * toggle and timing.
 */
public record VillageSettingsRequest(
        boolean npcEnabled,
        @Min(0) @Max(100) int npcTriggerPct,
        @Min(0) @Max(100) int npcRatioWood,
        @Min(0) @Max(100) int npcRatioClay,
        @Min(0) @Max(100) int npcRatioIron,
        @Min(0) @Max(100) int npcRatioCrop,
        @Min(0) int goldReserve,

        boolean autoScrapeEnabled,
        @Min(1) @Max(1440) int autoScrapeIntervalMinutes,

        boolean cropWatcherEnabled,
        @Min(0) @Max(100) int cropWatcherThresholdPct,
        @Min(0) int cropWatcherMinGold,

        boolean trainTroopsEnabled,
        @Min(1) @Max(1440) int trainTroopsMinMinutes,
        @Min(1) @Max(1440) int trainTroopsMaxMinutes
) {
}
