package com.travianbot.settings.api.dto;

import com.travianbot.settings.domain.Language;
import com.travianbot.settings.domain.Theme;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record GeneralSettingsRequest(
        @NotNull Theme theme,
        /* Language is a closed enum; Jackson deserializes strings like "EN" / "HR" to the enum value. */
        @NotNull Language language,
        boolean desktopNotificationsEnabled,
        boolean soundAlertsEnabled,
        @Min(1) int defaultCheckIntervalMinutes,
        @Min(value = 1, message = "must be at least 1 ms") @Max(value = 10_000, message = "must be at most 10000 ms") int minClickDelayMs,
        @Min(value = 1, message = "must be at least 1 ms") @Max(value = 10_000, message = "must be at most 10000 ms") int maxClickDelayMs,
        @Min(value = 1, message = "must allow at least 1 action per minute") @Max(value = 600, message = "must be at most 600 per minute") int maxActionsPerMinute,
        /**
         * Global kill-switch — when false the scheduled village scraper
         * does nothing. Manual UI scrapes are unaffected.
         */
        boolean villageScrapingEnabled,
        /**
         * Opt-in: schedulers may re-login automatically (once, cooldown-limited)
         * when they find the browser session gone.
         */
        boolean autoReloginEnabled
) {
    /**
     * Cross-field rule — max must not be less than min. Expressed as an {@code @AssertTrue} so the
     * bean-validation layer rejects the request before it reaches the service.
     */
    @AssertTrue(message = "maxClickDelayMs must be greater than or equal to minClickDelayMs")
    public boolean isClickDelayRangeValid() {
        return maxClickDelayMs >= minClickDelayMs;
    }
}
