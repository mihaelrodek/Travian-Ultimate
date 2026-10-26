package com.travianbot.notification.discord.api.dto;

import com.travianbot.notification.discord.domain.DiscordSettings;

import java.time.Instant;

/**
 * Wire shape for {@link DiscordSettings}. {@code configured} is a derived
 * flag the frontend uses to enable/disable the "Send test" button without
 * re-implementing the null-or-blank webhook check.
 */
public record DiscordSettingsResponse(
        boolean enabled,
        String webhookUrl,
        String botUsername,
        String botAvatarUrl,
        String mentionUserId,
        String mentionRoleId,
        boolean notifyIncomingAttack,
        boolean notifyIncomingRaid,
        boolean notifyReturnCatch,
        boolean notifyCaptcha,
        boolean notifySessionLost,
        boolean notifyResourceOverflow,
        Instant updatedAt,
        boolean configured
) {
    public static DiscordSettingsResponse from(DiscordSettings s) {
        boolean configured = s.getWebhookUrl() != null && !s.getWebhookUrl().isBlank();
        return new DiscordSettingsResponse(
                s.isEnabled(),
                s.getWebhookUrl(),
                s.getBotUsername(),
                s.getBotAvatarUrl(),
                s.getMentionUserId(),
                s.getMentionRoleId(),
                s.isNotifyIncomingAttack(),
                s.isNotifyIncomingRaid(),
                s.isNotifyReturnCatch(),
                s.isNotifyCaptcha(),
                s.isNotifySessionLost(),
                s.isNotifyResourceOverflow(),
                s.getUpdatedAt(),
                configured
        );
    }
}
