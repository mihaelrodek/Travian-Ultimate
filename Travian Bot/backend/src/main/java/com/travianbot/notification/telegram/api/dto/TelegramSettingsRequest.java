package com.travianbot.notification.telegram.api.dto;

import jakarta.validation.constraints.Size;

/**
 * PUT payload for {@code /api/telegram/settings}. Token / chat id may be
 * null/blank — the service normalises blank to null ("clear this value").
 */
public record TelegramSettingsRequest(
        boolean enabled,
        @Size(max = 200) String botToken,
        @Size(max = 64) String chatId,
        boolean notifyIncomingAttack,
        boolean notifyIncomingRaid,
        boolean notifyReturnCatch,
        boolean notifyCaptcha,
        boolean notifySessionLost,
        boolean notifyResourceOverflow
) {
}
