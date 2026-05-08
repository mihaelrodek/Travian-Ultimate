package com.travianbot.settings.api.dto;

import com.travianbot.settings.domain.GeneralSettings;
import com.travianbot.settings.domain.Language;
import com.travianbot.settings.domain.Theme;

public record GeneralSettingsResponse(
        Theme theme,
        /*
         * Serializes as "EN" / "HR" via Jackson's default enum-name rendering. The
         * frontend treats it as a string-literal union, which mirrors the closed
         * set here — any change to {@link Language} requires updating the TS type.
         */
        Language language,
        boolean desktopNotificationsEnabled,
        int defaultCheckIntervalMinutes,
        int minClickDelayMs,
        int maxClickDelayMs
) {
    public static GeneralSettingsResponse from(GeneralSettings s) {
        return new GeneralSettingsResponse(
                s.getTheme(),
                s.getLanguageEnum(),
                s.isDesktopNotificationsEnabled(),
                s.getDefaultCheckIntervalMinutes(),
                s.getMinClickDelayMs(),
                s.getMaxClickDelayMs()
        );
    }
}
