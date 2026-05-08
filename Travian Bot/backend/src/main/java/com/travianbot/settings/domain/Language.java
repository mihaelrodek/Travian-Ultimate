package com.travianbot.settings.domain;

import java.util.Locale;

/**
 * Supported UI languages. Only the two codes we actually ship translations for —
 * constraining the set at the domain layer means the frontend picker, backend
 * validation, and DB persistence all agree on the same closed set rather than
 * accepting any BCP-47 string.
 *
 * <p>Stored on {@link GeneralSettings} as the enum name ("EN" / "HR").
 * Jackson serializes enums as their name by default, so the React layer receives
 * the same string.
 */
public enum Language {
    EN,
    HR;

    /** The default used for brand-new installs and as a fallback for unknown legacy values. */
    public static Language defaultLanguage() {
        return EN;
    }

    /**
     * Parse a stored string (possibly legacy lowercase like {@code "en"}) into a
     * {@link Language}, falling back to {@link #defaultLanguage()} on anything
     * unrecognized. Never throws — callers are free to upgrade silently.
     */
    public static Language parseOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultLanguage();
        }
        try {
            return Language.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultLanguage();
        }
    }
}
