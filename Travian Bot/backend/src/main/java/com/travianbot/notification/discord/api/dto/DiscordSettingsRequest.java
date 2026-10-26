package com.travianbot.notification.discord.api.dto;

import jakarta.validation.constraints.Size;

/**
 * PUT payload for {@code /api/discord/settings}. Optional strings may be
 * null/empty — the service treats blank as "clear this value".
 */
public record DiscordSettingsRequest(
        boolean enabled,
        @Size(max = 500) String webhookUrl,
        @Size(max = 80) String botUsername,
        @Size(max = 500) String botAvatarUrl,
        @Size(max = 50) String mentionUserId,
        @Size(max = 50) String mentionRoleId,
        boolean notifyIncomingAttack,
        boolean notifyIncomingRaid,
        boolean notifyReturnCatch,
        boolean notifyCaptcha,
        boolean notifySessionLost,
        boolean notifyResourceOverflow
) {
}
