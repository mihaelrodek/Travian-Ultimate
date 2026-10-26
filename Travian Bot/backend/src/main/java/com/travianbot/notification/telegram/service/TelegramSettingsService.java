package com.travianbot.notification.telegram.service;

import com.travianbot.notification.telegram.api.dto.TelegramSettingsRequest;
import com.travianbot.notification.telegram.domain.TelegramSettings;
import com.travianbot.notification.telegram.persistence.TelegramSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/write surface for the singleton {@link TelegramSettings} row. Mirrors
 * {@code DiscordSettingsService} — lazy-create on first access so a fresh DB
 * needs no manual insert.
 */
@Service
@Transactional
public class TelegramSettingsService {

    private final TelegramSettingsRepository repository;

    public TelegramSettingsService(TelegramSettingsRepository repository) {
        this.repository = repository;
    }

    public TelegramSettings get() {
        return repository.findById(TelegramSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(new TelegramSettings()));
    }

    public TelegramSettings update(TelegramSettingsRequest req) {
        TelegramSettings s = get();
        s.setEnabled(req.enabled());
        s.setBotToken(req.botToken());
        s.setChatId(req.chatId());
        s.setNotifyIncomingAttack(req.notifyIncomingAttack());
        s.setNotifyIncomingRaid(req.notifyIncomingRaid());
        s.setNotifyReturnCatch(req.notifyReturnCatch());
        s.setNotifyCaptcha(req.notifyCaptcha());
        s.setNotifySessionLost(req.notifySessionLost());
        s.setNotifyResourceOverflow(req.notifyResourceOverflow());
        return repository.save(s);
    }
}
