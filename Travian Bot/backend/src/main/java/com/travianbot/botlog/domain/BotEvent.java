package com.travianbot.botlog.domain;

import com.travianbot.account.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row in the "bot did something" activity log. Each event answers
 * <em>what happened</em>, <em>to which account/village</em>, <em>did it
 * work</em>, and <em>the message we'd show a human</em>.
 * <p>
 * This is intentionally a thin, write-mostly journal:
 * <ul>
 *   <li>Services call {@code BotEventService.record(...)} from any point
 *       where "the bot just did a thing" is interesting to show in the UI —
 *       scrape finished, a queued build started, a farmlist went out,
 *       login succeeded, etc.</li>
 *   <li>The frontend Log screen reads it back paginated, newest first.</li>
 * </ul>
 * <p>
 * {@code action} is deliberately a free-form {@code String} — the set of
 * actions grows as new bot features land, and promoting this to an enum
 * every time adds friction without a lot of upside. We pick short UPPER_SNAKE
 * conventions (e.g. {@code SCRAPE_VILLAGE}, {@code BUILD_START},
 * {@code FARMLIST_SEND}, {@code LOGIN}) so filtering is predictable.
 * <p>
 * {@code villageId} is a raw Long (not a {@link
 * com.travianbot.village.domain.Village} relationship). The log is a journal:
 * if a village is later deleted, we still want its history visible in the
 * log for debugging. Using a raw id (no FK) keeps the row alive after the
 * village disappears. Account cleanup is handled explicitly by
 * {@link com.travianbot.botlog.service.BotEventService}'s deletion listener.
 * <p>
 * An index on {@code (account_id, created_at DESC)} matches the hot query
 * shape — "show me the newest events for this account" — and is cheap to
 * maintain for an append-only table.
 */
@Entity
@Table(
        name = "bot_event",
        indexes = {
                @Index(name = "idx_bot_event_account_created",
                        columnList = "account_id, created_at DESC"),
                @Index(name = "idx_bot_event_created", columnList = "created_at DESC")
        }
)
public class BotEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Account the event is scoped to. Lazy fetch because the common access
     * pattern is "list events for account X" — we already know the id from
     * the query, no need to hydrate the Account row per log line.
     */
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Optional village scope. Many events are account-wide (login, logout,
     * farmlist send-all); those leave this null. Events tied to a specific
     * village (scrape, build start) set it.
     */
    @Column(name = "village_id")
    private Long villageId;

    @Column(nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private BotEventOutcome outcome;

    /**
     * Human-readable detail line. TEXT column because messages can include
     * browser error dumps or multi-sentence explanations.
     */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BotEvent() {
    }

    public BotEvent(Account account, Long villageId, String action,
                    BotEventOutcome outcome, String message) {
        this.account = account;
        this.villageId = villageId;
        this.action = action;
        this.outcome = outcome;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public Long getVillageId() { return villageId; }
    public String getAction() { return action; }
    public BotEventOutcome getOutcome() { return outcome; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
