package com.travianbot.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * App-wide settings. Single-row table — {@link #SINGLETON_ID} is used for the one-and-only row.
 */
@Entity
@Table(name = "general_settings")
public class GeneralSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Theme theme = Theme.SYSTEM;

    /**
     * UI language. Stored as a short string so the DB column (originally a
     * BCP-47 text field defaulting to {@code "en"}) keeps accepting legacy
     * lowercase values without Hibernate throwing on startup. The service layer
     * normalises whatever is loaded through {@link Language#parseOrDefault(String)}
     * so downstream callers always see a valid enum value.
     *
     * <p>We intentionally avoid {@code @Enumerated(EnumType.STRING)} here: a
     * legacy row containing {@code "en"} would fail to map to {@link Language#EN},
     * which is uppercase.
     */
    @Column(length = 16, nullable = false)
    private String language = Language.defaultLanguage().name();

    @Column(name = "desktop_notifications_enabled", nullable = false)
    private boolean desktopNotificationsEnabled = true;

    /**
     * Play a short in-browser sound when a critical event (currently: a new
     * incoming attack) is detected while the app is open. Off by default —
     * audio that starts on its own is intrusive, so the user opts in. The tone
     * is synthesised via the Web Audio API on the frontend, so there's no
     * asset to ship; this flag is the only server-side state.
     */
    @Column(name = "sound_alerts_enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean soundAlertsEnabled = false;

    @Column(name = "default_check_interval_minutes", nullable = false)
    private int defaultCheckIntervalMinutes = 10;

    /**
     * Lower bound (inclusive) of the random delay in milliseconds that the browser automation
     * waits before every click/type/navigate. Together with {@link #maxClickDelayMs} this forms
     * a uniform random range — flat intervals are the single easiest bot-detection signal, so we
     * never want a deterministic wait time. Defaults tuned to feel like a slightly distracted
     * human, not a speed-runner.
     *
     * <p>Note the {@code columnDefinition}: Hibernate's {@code ddl-auto: update} emits a bare
     * {@code ALTER TABLE ... ADD COLUMN ... NOT NULL} which PostgreSQL rejects if the table
     * already contains rows. Inlining {@code DEFAULT} makes the ALTER idempotent without a
     * separate migration step.
     */
    @Column(name = "min_click_delay_ms", nullable = false,
            columnDefinition = "INTEGER NOT NULL DEFAULT 80")
    private int minClickDelayMs = 80;

    /**
     * Upper bound (inclusive) of the random click delay. Kept well under a second by default so
     * the bot doesn't feel sluggish — the user can push it up to 2000+ ms if they want an
     * extremely cautious profile. Same DEFAULT trick as {@link #minClickDelayMs}.
     */
    @Column(name = "max_click_delay_ms", nullable = false,
            columnDefinition = "INTEGER NOT NULL DEFAULT 320")
    private int maxClickDelayMs = 320;

    /**
     * Hard ceiling on browser actions (navigations/clicks) per minute per
     * account, enforced by
     * {@link com.travianbot.bot.client.browser.ActionRateLimiter}. Where the
     * click-delay range randomises the gap between individual actions, this
     * caps the <em>sustained</em> rate so a burst of scheduled work (several
     * villages coming due in the same tick) can't fire off dozens of requests
     * in a few seconds and light up Travian's rate analytics. The bucket also
     * allows a short burst up to this value before pacing kicks in.
     * <p>
     * Default 20/min ≈ one action every 3 s sustained — comfortably human for
     * a focused play session. Same inlined {@code DEFAULT} trick as the
     * click-delay columns so {@code ddl-auto: update} adds it to existing
     * tables cleanly.
     */
    @Column(name = "max_actions_per_minute", nullable = false,
            columnDefinition = "INTEGER NOT NULL DEFAULT 20")
    private int maxActionsPerMinute = 20;

    /**
     * Global kill-switch for the per-village auto-scrape scheduler. When
     * {@code false}, {@link com.travianbot.village.service.VillageScrapeScheduler}
     * short-circuits its tick — no village gets scraped automatically, no
     * matter what its per-village {@code autoScrapeEnabled} flag says.
     * Manual UI scrapes (Scan now / Scrape current village / Scrape all)
     * still go through because they don't run through the scheduler.
     * <p>
     * The inlined {@code DEFAULT TRUE} keeps Hibernate's {@code ddl-auto:
     * update} happy on existing tables — same trick used for the click-delay
     * columns above.
     */
    @Column(name = "village_scraping_enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean villageScrapingEnabled = true;

    /**
     * Opt-in: when a scheduled task finds the browser session gone (crash,
     * server-side logout), attempt one automatic re-login and continue.
     * Default OFF — unattended logins can walk straight into a captcha loop,
     * so the user has to consciously enable this. Attempts are additionally
     * cooldown-limited per account by
     * {@link com.travianbot.account.service.SessionRecoveryService}.
     */
    @Column(name = "auto_relogin_enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean autoReloginEnabled = false;

    // Discord integration was promoted to a dedicated entity
    // ({@link com.travianbot.notification.discord.domain.DiscordSettings})
    // because the surface grew past a couple of bool/string columns. The
    // legacy {@code discord_webhook_url} / {@code discord_notify_on_incoming}
    // columns may still exist on older DBs (Hibernate's {@code ddl-auto:
    // update} never drops columns) — they're now unused and harmless.

    // villageScrapeIntervalMinutes was moved to {@link com.travianbot.village.domain.VillageSettings#autoScrapeIntervalMinutes}.
    // A global interval didn't fit real play — a capital under attack wants 2–5 min polling while
    // a quiet support village is fine at 30–60. The legacy column is dropped by
    // {@link com.travianbot.settings.persistence.GeneralSettingsSchemaUpgrade}.

    public GeneralSettings() {
    }

    public Long getId() { return id; }

    public Theme getTheme() { return theme; }
    public void setTheme(Theme theme) { this.theme = theme; }

    /** Raw column value — may be legacy lowercase; prefer {@link #getLanguageEnum()}. */
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    /** Parsed {@link Language} view — always returns a valid enum, falling back to {@link Language#defaultLanguage()}. */
    public Language getLanguageEnum() {
        return Language.parseOrDefault(language);
    }

    public void setLanguageEnum(Language lang) {
        this.language = lang.name();
    }

    public boolean isDesktopNotificationsEnabled() { return desktopNotificationsEnabled; }
    public void setDesktopNotificationsEnabled(boolean desktopNotificationsEnabled) {
        this.desktopNotificationsEnabled = desktopNotificationsEnabled;
    }

    public boolean isSoundAlertsEnabled() { return soundAlertsEnabled; }
    public void setSoundAlertsEnabled(boolean soundAlertsEnabled) {
        this.soundAlertsEnabled = soundAlertsEnabled;
    }

    public int getDefaultCheckIntervalMinutes() { return defaultCheckIntervalMinutes; }
    public void setDefaultCheckIntervalMinutes(int defaultCheckIntervalMinutes) {
        this.defaultCheckIntervalMinutes = defaultCheckIntervalMinutes;
    }

    public int getMinClickDelayMs() { return minClickDelayMs; }
    public void setMinClickDelayMs(int minClickDelayMs) { this.minClickDelayMs = minClickDelayMs; }

    public int getMaxClickDelayMs() { return maxClickDelayMs; }
    public void setMaxClickDelayMs(int maxClickDelayMs) { this.maxClickDelayMs = maxClickDelayMs; }

    public int getMaxActionsPerMinute() { return maxActionsPerMinute; }
    public void setMaxActionsPerMinute(int maxActionsPerMinute) { this.maxActionsPerMinute = maxActionsPerMinute; }

    public boolean isVillageScrapingEnabled() { return villageScrapingEnabled; }
    public void setVillageScrapingEnabled(boolean villageScrapingEnabled) {
        this.villageScrapingEnabled = villageScrapingEnabled;
    }

    public boolean isAutoReloginEnabled() { return autoReloginEnabled; }
    public void setAutoReloginEnabled(boolean autoReloginEnabled) {
        this.autoReloginEnabled = autoReloginEnabled;
    }

}
