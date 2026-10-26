package com.travianbot.settings.service;

import com.travianbot.settings.api.dto.GeneralSettingsRequest;
import com.travianbot.settings.domain.GeneralSettings;
import com.travianbot.settings.domain.Language;
import com.travianbot.settings.persistence.GeneralSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GeneralSettingsService {

    private final GeneralSettingsRepository repository;

    public GeneralSettingsService(GeneralSettingsRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the singleton settings row, creating it with defaults if this is a fresh DB.
     *
     * <p>Also performs a one-shot normalisation of the {@code language} column — legacy rows
     * contain lowercase BCP-47 tags like {@code "en"} which would fail to round-trip through the
     * {@link Language} enum. We upgrade them in place the first time we see them, so subsequent
     * reads return the canonical uppercase form without further work.
     */
    public GeneralSettings get() {
        GeneralSettings s = repository.findById(GeneralSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(new GeneralSettings()));
        Language parsed = Language.parseOrDefault(s.getLanguage());
        if (!parsed.name().equals(s.getLanguage())) {
            s.setLanguageEnum(parsed);
            s = repository.save(s);
        }
        return s;
    }

    public GeneralSettings update(GeneralSettingsRequest req) {
        GeneralSettings s = get();
        s.setTheme(req.theme());
        s.setLanguageEnum(req.language());
        s.setDesktopNotificationsEnabled(req.desktopNotificationsEnabled());
        s.setSoundAlertsEnabled(req.soundAlertsEnabled());
        s.setDefaultCheckIntervalMinutes(req.defaultCheckIntervalMinutes());
        s.setMinClickDelayMs(req.minClickDelayMs());
        s.setMaxClickDelayMs(req.maxClickDelayMs());
        s.setMaxActionsPerMinute(req.maxActionsPerMinute());
        s.setVillageScrapingEnabled(req.villageScrapingEnabled());
        s.setAutoReloginEnabled(req.autoReloginEnabled());
        return repository.save(s);
    }

    /**
     * Read-only pair used by the browser automation layer — keeps callers from depending on the
     * whole settings entity just to know how long to pause before the next click.
     */
    @Transactional(readOnly = true)
    public ClickDelayRange getClickDelayRange() {
        GeneralSettings s = get();
        return new ClickDelayRange(s.getMinClickDelayMs(), s.getMaxClickDelayMs());
    }

    /**
     * Read-only accessor for the browser-action rate cap. Kept separate from
     * the entity so the bot-client layer (which must stay cycle-free) can pull
     * just this number through a supplier, the same way it does for the click
     * delay range.
     */
    @Transactional(readOnly = true)
    public int getMaxActionsPerMinute() {
        return get().getMaxActionsPerMinute();
    }

    public record ClickDelayRange(int minMs, int maxMs) {
    }
}
