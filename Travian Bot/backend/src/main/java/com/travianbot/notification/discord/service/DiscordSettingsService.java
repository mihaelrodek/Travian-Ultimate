package com.travianbot.notification.discord.service;

import com.travianbot.notification.discord.api.dto.DiscordSettingsRequest;
import com.travianbot.notification.discord.domain.DiscordSettings;
import com.travianbot.notification.discord.persistence.DiscordSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write surface for the singleton {@link DiscordSettings} row. Mirrors
 * the existing {@code GeneralSettingsService} pattern — lazy-create on
 * first access so a fresh DB doesn't need a manual insert.
 */
@Service
@Transactional
public class DiscordSettingsService {

    private final DiscordSettingsRepository repository;

    public DiscordSettingsService(DiscordSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the singleton settings row, creating it with defaults if this
     * is a fresh DB. Default state has {@code enabled=false} and no webhook
     * — the notifier short-circuits, nothing leaks.
     */
    public DiscordSettings get() {
        return repository.findById(DiscordSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(new DiscordSettings()));
    }

    /**
     * Apply a PUT payload. Note that {@code setX()} on the entity normalises
     * blanks to null — we don't need to pre-trim here.
     */
    public DiscordSettings update(DiscordSettingsRequest req) {
        DiscordSettings s = get();
        s.setEnabled(req.enabled());
        s.setWebhookUrl(req.webhookUrl());
        s.setBotUsername(req.botUsername());
        s.setBotAvatarUrl(req.botAvatarUrl());
        s.setMentionUserId(req.mentionUserId());
        s.setMentionRoleId(req.mentionRoleId());
        s.setNotifyIncomingAttack(req.notifyIncomingAttack());
        s.setNotifyIncomingRaid(req.notifyIncomingRaid());
        s.setNotifyReturnCatch(req.notifyReturnCatch());
        s.setNotifyCaptcha(req.notifyCaptcha());
        s.setNotifySessionLost(req.notifySessionLost());
        s.setNotifyResourceOverflow(req.notifyResourceOverflow());
        return repository.save(s);
    }
}
