package com.travianbot.settings.domain;

/**
 * UI theme preference. {@code SYSTEM} delegates to the OS via {@code prefers-color-scheme}.
 */
public enum Theme {
    LIGHT,
    DARK,
    SYSTEM
}
