package com.travianbot.notification.telegram.api.dto;

import com.travianbot.notification.telegram.domain.TelegramSettings;

import java.time.Instant;

/**
 * Wire shape for {@link TelegramSettings}. {@code configured} is a derived
 * "token AND chat id both set" flag the frontend uses to enable the Send-test
 * button and gate the dependent inputs, mirroring the Discord page.
 */
public record TelegramSettingsResponse(
        boolean enabled,
        String botToken,
        String chatId,
        boolean notifyIncomingAttack,
        boolean notifyIncomingRaid,
        boolean notifyReturnCatch,
        boolean notifyCaptcha,
        boolean notifySessionLost,
        boolean notifyResourceOverflow,
        Instant updatedAt,
        boolean configured
) {
    public static TelegramSettingsResponse from(TelegramSettings s) {
        boolean configured = s.getBotToken() != null && !s.getBotToken().isBlank()
                && s.getChatId() != null && !s.getChatId().isBlank();
        return new TelegramSettingsResponse(
                s.isEnabled(),
                s.getBotToken(),
                s.getChatId(),
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
