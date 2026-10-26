package com.travianbot.notification.discord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton settings row for Discord integration. Same shape as
 * {@code GeneralSettings} — exactly one row with id = {@link #SINGLETON_ID}.
 * <p>
 * Why a dedicated entity instead of more columns on {@code GeneralSettings}?
 * The Discord surface grows in clusters (notification triggers, channel
 * mappings, message-template fields, alternate webhook URLs for severity
 * tiers); folding it into the shared settings table would turn that row
 * into a god-object. Splitting now keeps the change radius small later.
 * <p>
 * <strong>Master switch.</strong> {@link #enabled} is the single OFF
 * override — false here suppresses every Discord post regardless of the
 * per-event toggles. The webhook URL is also independently guarded
 * (null/blank → no posts) so a half-filled form can't leak.
 */
@Entity
@Table(name = "discord_settings")
public class DiscordSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /**
     * Master switch. {@code false} suppresses every Discord post. Default
     * false so a fresh install never accidentally pings a stale webhook.
     */
    @Column(name = "enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean enabled = false;

    /**
     * Discord channel webhook URL. Get it from the channel's Integrations →
     * Webhooks panel. Anyone with this URL can post — treat as secret.
     */
    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    /** Override the displayed sender name on every webhook post. Discord uses "Captain Hook" by default. */
    @Column(name = "bot_username", length = 80)
    private String botUsername = "Travian Bot";

    /** Optional avatar URL — square PNG/JPG works best (Discord rescales to 128×128). */
    @Column(name = "bot_avatar_url", length = 500)
    private String botAvatarUrl;

    /**
     * Optional Discord user-ping prefix to inject into every alert ("<@USER_ID>").
     * Stored as the raw mention string so the user can paste exactly what
     * Discord's right-click → Copy ID produces.
     */
    @Column(name = "mention_user_id", length = 50)
    private String mentionUserId;

    /** Optional role-ping ("<@&ROLE_ID>") — same idea, but pings a Discord role instead of a person. */
    @Column(name = "mention_role_id", length = 50)
    private String mentionRoleId;

    // ---- Per-event notification toggles -----------------------------------

    /** Post when a brand-new incoming-attack row is recorded (paste-in or scrape). */
    @Column(name = "notify_incoming_attack", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyIncomingAttack = true;

    /** Post on raids the same way as full attacks (separate toggle so a chatty raid pattern can be muted). */
    @Column(name = "notify_incoming_raid", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyIncomingRaid = true;

    /** Post when the planner thinks a return-catch snipe is launchable now. */
    @Column(name = "notify_return_catch", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean notifyReturnCatch = false;

    /** Post when a captcha challenge is detected by the browser session. */
    @Column(name = "notify_captcha", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyCaptcha = true;

    /** Post when an account's browser session gets logged out unexpectedly. */
    @Column(name = "notify_session_lost", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean notifySessionLost = false;

    /**
     * Post when a scraped village's warehouse or granary crosses the
     * overflow threshold (~95% full) — resources are about to be wasted.
     */
    @Column(name = "notify_resource_overflow", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyResourceOverflow = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public DiscordSettings() {
    }

    public Long getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) {
        // Treat blank as null so the "is configured?" check at the
        // notifier is one isEmpty() guard.
        this.webhookUrl = (webhookUrl == null || webhookUrl.isBlank()) ? null : webhookUrl.trim();
        touch();
    }

    public String getBotUsername() { return botUsername; }
    public void setBotUsername(String botUsername) {
        this.botUsername = (botUsername == null || botUsername.isBlank()) ? null : botUsername.trim();
        touch();
    }

    public String getBotAvatarUrl() { return botAvatarUrl; }
    public void setBotAvatarUrl(String botAvatarUrl) {
        this.botAvatarUrl = (botAvatarUrl == null || botAvatarUrl.isBlank()) ? null : botAvatarUrl.trim();
        touch();
    }

    public String getMentionUserId() { return mentionUserId; }
    public void setMentionUserId(String mentionUserId) {
        this.mentionUserId = (mentionUserId == null || mentionUserId.isBlank()) ? null : mentionUserId.trim();
        touch();
    }

    public String getMentionRoleId() { return mentionRoleId; }
    public void setMentionRoleId(String mentionRoleId) {
        this.mentionRoleId = (mentionRoleId == null || mentionRoleId.isBlank()) ? null : mentionRoleId.trim();
        touch();
    }

    public boolean isNotifyIncomingAttack() { return notifyIncomingAttack; }
    public void setNotifyIncomingAttack(boolean v) { this.notifyIncomingAttack = v; touch(); }

    public boolean isNotifyIncomingRaid() { return notifyIncomingRaid; }
    public void setNotifyIncomingRaid(boolean v) { this.notifyIncomingRaid = v; touch(); }

    public boolean isNotifyReturnCatch() { return notifyReturnCatch; }
    public void setNotifyReturnCatch(boolean v) { this.notifyReturnCatch = v; touch(); }

    public boolean isNotifyCaptcha() { return notifyCaptcha; }
    public void setNotifyCaptcha(boolean v) { this.notifyCaptcha = v; touch(); }

    public boolean isNotifySessionLost() { return notifySessionLost; }
    public void setNotifySessionLost(boolean v) { this.notifySessionLost = v; touch(); }

    public boolean isNotifyResourceOverflow() { return notifyResourceOverflow; }
    public void setNotifyResourceOverflow(boolean v) { this.notifyResourceOverflow = v; touch(); }

    public Instant getUpdatedAt() { return updatedAt; }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
