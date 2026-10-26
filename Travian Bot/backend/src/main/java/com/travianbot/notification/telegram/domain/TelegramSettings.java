package com.travianbot.notification.telegram.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton settings row for the Telegram integration — same shape and
 * lifecycle as {@code DiscordSettings} (exactly one row, id =
 * {@link #SINGLETON_ID}), so the two channels stay symmetrical.
 * <p>
 * Delivery uses the Telegram Bot API: create a bot with {@code @BotFather} to
 * get the {@link #botToken}, then message the bot (or add it to a group) and
 * put the target {@link #chatId} here. {@link #enabled} is the master OFF
 * switch; both token and chat id are independently guarded so a half-filled
 * form never posts.
 * <p>
 * WARNING — {@code botToken} is stored plaintext, same caveat as every other
 * secret in this single-user local bot; anyone with the token can post as the
 * bot. Fine for local use, encrypt before this ever leaves one machine.
 */
@Entity
@Table(name = "telegram_settings")
public class TelegramSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean enabled = false;

    /** Bot token from {@code @BotFather}, e.g. {@code 123456:ABC-DEF...}. */
    @Column(name = "bot_token", length = 200)
    private String botToken;

    /** Target chat id — a user id, group id (negative), or {@code @channelname}. */
    @Column(name = "chat_id", length = 64)
    private String chatId;

    // ---- Per-event notification toggles (mirror DiscordSettings) -----------

    @Column(name = "notify_incoming_attack", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyIncomingAttack = true;

    @Column(name = "notify_incoming_raid", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyIncomingRaid = true;

    @Column(name = "notify_return_catch", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean notifyReturnCatch = false;

    @Column(name = "notify_captcha", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyCaptcha = true;

    @Column(name = "notify_session_lost", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean notifySessionLost = false;

    @Column(name = "notify_resource_overflow", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean notifyResourceOverflow = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public TelegramSettings() {
    }

    public Long getId() { return id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }

    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) {
        this.botToken = (botToken == null || botToken.isBlank()) ? null : botToken.trim();
        touch();
    }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) {
        this.chatId = (chatId == null || chatId.isBlank()) ? null : chatId.trim();
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
