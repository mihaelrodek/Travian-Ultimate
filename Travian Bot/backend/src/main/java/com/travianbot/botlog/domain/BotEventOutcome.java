package com.travianbot.botlog.domain;

/**
 * Coarse success/warn/fail classification for a bot log event.
 * <p>
 * Kept intentionally small — the UI colours rows by these three buckets,
 * so introducing a fourth value would require CSS + i18n changes. Anything
 * more nuanced belongs in {@code message}.
 */
public enum BotEventOutcome {
    /** The action completed as intended. */
    OK,
    /** The action completed but with a caveat the user may want to see
     * (e.g. queue deferred because capacity was full, partial scrape). */
    WARN,
    /** The action failed outright and the user likely needs to intervene. */
    ERROR
}
