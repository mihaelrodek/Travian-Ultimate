package com.travianbot.account.api.dto;

import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.HeroAttribute;
import com.travianbot.account.domain.Tribe;

import java.util.List;

/**
 * Settings returned to the frontend. Proxy password is omitted (write-only).
 * <p>
 * Tribe is included as read-only state — auto-detected server-side from the
 * in-game profile on first login. The frontend renders it (or doesn't) but
 * never edits it. Sitter, oasis-farming, and NPC fields have been removed —
 * NPC moved to VillageSettings.
 */
public record AccountSettingsResponse(
        Long accountId,
        Tribe tribe,

        HeroAttribute heroAutoLevel,
        int heroHealThresholdPct,
        boolean heroAutoAdventure,
        int heroMinHealthForAdventure,

        boolean alertOnAttack,
        boolean alertIncludeTroopCounts,
        List<String> alertChannels,

        String proxyUrl,
        String proxyUsername,
        boolean proxyPasswordSet,

        List<ActiveWindowDto> activeWindows
) {
    public static AccountSettingsResponse from(AccountSettings s) {
        return new AccountSettingsResponse(
                s.getAccount().getId(),
                s.getTribe(),

                s.getHeroAutoLevel(),
                s.getHeroHealThresholdPct(),
                s.isHeroAutoAdventure(),
                s.getHeroMinHealthForAdventure(),

                s.isAlertOnAttack(),
                s.isAlertIncludeTroopCounts(),
                splitChannels(s.getAlertChannels()),

                s.getProxyUrl(),
                s.getProxyUsername(),
                s.getProxyPassword() != null && !s.getProxyPassword().isBlank(),

                s.getActiveWindows().stream().map(ActiveWindowDto::from).toList()
        );
    }

    private static List<String> splitChannels(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split("\\s*,\\s*"));
    }
}
