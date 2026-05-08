package com.travianbot.account.api.dto;

import com.travianbot.account.domain.HeroAttribute;
import com.travianbot.account.domain.Tribe;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Full settings payload. PUT replaces everything — simpler than PATCH for a form UI.
 * <p>
 * {@code tribe} is NOT user-editable from the UI (the backend auto-detects it on first
 * login), but we still accept it here so the frontend can round-trip whatever value it
 * loaded without needing a separate endpoint. Sitter, oasis-farming, and NPC fields have
 * been removed — NPC is now a per-village concern (see VillageSettingsRequest).
 * <p>
 * {@code proxyPassword} is optional on update: null = leave unchanged, "" = clear.
 */
public record AccountSettingsRequest(
        Tribe tribe,

        @NotNull HeroAttribute heroAutoLevel,
        @Min(0) @Max(100) int heroHealThresholdPct,
        boolean heroAutoAdventure,
        @Min(0) @Max(100) int heroMinHealthForAdventure,

        boolean alertOnAttack,
        boolean alertIncludeTroopCounts,
        List<String> alertChannels,

        String proxyUrl,
        String proxyUsername,
        String proxyPassword,

        @Valid List<ActiveWindowDto> activeWindows
) {
}
