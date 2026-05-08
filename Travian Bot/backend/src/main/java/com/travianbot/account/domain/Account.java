package com.travianbot.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Travian account the bot can operate on.
 * <p>
 * WARNING — {@code password} is stored in plaintext. Acceptable for a single-user, local-only bot;
 * must be replaced with symmetric encryption (e.g. AES-GCM keyed from a user-supplied master password)
 * before this project handles any account that isn't yours.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String serverUrl;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    /**
     * Human-readable alias shown in the sidebar and on the account detail
     * header. Set by the user when adding the account; if they leave it
     * blank we default to {@link #username} so every row always has a
     * printable label and the sidebar never renders an empty cell.
     * <p>
     * Deliberately NOT unique — two accounts on different servers can share
     * the same alias ("Main", "Sim") without collision headaches.
     */
    @Column(nullable = false, length = 100)
    private String nickname;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant lastLoginAt;

    @Column(length = 32)
    private String lastLoginStatus;

    @Column(length = 1024)
    private String lastLoginMessage;

    /**
     * Whether the account currently has an active Travian Plus subscription.
     * <p>
     * Populated by {@code ScrapingService} from the dorf1 HTML every time we
     * scrape a village — the Plus badge sits in the top-right premium bar so
     * any dorf1 hit refreshes it. Defaults to {@code false} on first boot and
     * stays at its last scraped value when we can't tell (e.g. the page didn't
     * parse, the user is logged out).
     * <p>
     * Read by {@code BuildQueueCapacity} to decide whether a village can
     * concurrently run an extra build slot per the in-game queue rules.
     */
    @Column(name = "plus_account_active", nullable = false)
    private boolean plusAccountActive = false;

    /** When the plus flag was last refreshed — null means we've never scraped it. */
    @Column(name = "plus_account_checked_at")
    private Instant plusAccountCheckedAt;

    /**
     * Offset from UTC of the game server in whole minutes (e.g. 60 for UTC+01:00,
     * -300 for UTC-05:00). Populated by the scraper from the footer "(UTC +01:00)"
     * string that Travian renders alongside the server clock.
     * <p>
     * We persist the offset rather than a raw "serverNow" timestamp because the
     * local machine's clock is the ground truth for "now" — we only need to know
     * the server's zone so the UI can render scheduled-end timestamps in either
     * the player's wall-clock or the in-game clock. The user's bot host is
     * typically +1h ahead of the game server; storing the delta once saves us
     * from re-deriving it on every page.
     */
    @Column(name = "server_utc_offset_minutes")
    private Integer serverUtcOffsetMinutes;

    /** When the server-time offset was last refreshed. Null → never scraped. */
    @Column(name = "server_time_checked_at")
    private Instant serverTimeCheckedAt;

    protected Account() {
    }

    public Account(String serverUrl, String username, String password) {
        this(serverUrl, username, password, username);
    }

    public Account(String serverUrl, String username, String password, String nickname) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.nickname = (nickname == null || nickname.isBlank()) ? username : nickname;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getServerUrl() { return serverUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) {
        this.nickname = (nickname == null || nickname.isBlank()) ? this.username : nickname;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public String getLastLoginStatus() { return lastLoginStatus; }
    public String getLastLoginMessage() { return lastLoginMessage; }

    public boolean isPlusAccountActive() { return plusAccountActive; }
    public Instant getPlusAccountCheckedAt() { return plusAccountCheckedAt; }

    public Integer getServerUtcOffsetMinutes() { return serverUtcOffsetMinutes; }
    public Instant getServerTimeCheckedAt() { return serverTimeCheckedAt; }

    /**
     * Record a fresh Plus-status reading. Stamps {@code plusAccountCheckedAt}
     * regardless of whether the boolean changed, so callers can tell "we
     * checked 30 s ago and saw no plus" apart from "we've never checked".
     */
    public void recordPlusStatus(boolean active) {
        this.plusAccountActive = active;
        this.plusAccountCheckedAt = Instant.now();
    }

    /**
     * Record the server's UTC offset. Null is a valid reading ("we scraped
     * but couldn't find the footer") — we still stamp the timestamp so the
     * caller can tell this apart from "never scraped".
     */
    public void recordServerUtcOffset(Integer offsetMinutes) {
        this.serverUtcOffsetMinutes = offsetMinutes;
        this.serverTimeCheckedAt = Instant.now();
    }

    public void recordLoginAttempt(String status, String message) {
        this.lastLoginAt = Instant.now();
        this.lastLoginStatus = status;
        this.lastLoginMessage = message;
    }

    public void updateCredentials(String serverUrl, String username, String password) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
    }
}
