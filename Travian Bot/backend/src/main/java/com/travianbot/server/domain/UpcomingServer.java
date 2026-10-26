package com.travianbot.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One upcoming Travian world parsed from the public calendar at
 * {@code https://www.travian.com/international/calendar}.
 * <p>
 * Distinct from {@link Server}: an {@code UpcomingServer} represents an
 * <em>announced</em> world that may not yet have a live {@code map.sql} URL —
 * the row exists so the UI can show "ts5 EU x1 starts Aug 12" before the
 * world actually opens. Once the world is up and our discovery probe finds it,
 * {@link #linkedServerId} can be filled in to bridge the two records.
 * <p>
 * Uniqueness is on {@link #calendarId}, a synthetic stable id concatenated
 * from country + world-name + start-date so re-scraping the same row UPDATES
 * rather than inserts a duplicate.
 */
@Entity
@Table(
        name = "upcoming_server",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_upcoming_server_calendar_id",
                columnNames = "calendar_id"
        )
)
public class UpcomingServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_id", nullable = false, length = 200)
    private String calendarId;

    /**
     * Continent-level community ("Europe" / "America" / "Asia" / "Arabics" /
     * "International" / "CzSk") OR {@code "Special"} for non-continental
     * special events (Travian Schild, Community Week, …) that the calendar
     * sometimes lists. Nullable so a future "we don't even know that much"
     * row never trips a NOT-NULL check — the scraper always fills it with a
     * sensible fallback.
     */
    @Column(length = 80)
    private String region;

    @Column(name = "world_name", nullable = false, length = 150)
    private String worldName;

    @Column
    private Integer speed;

    @Column(name = "scheduled_start_at")
    private Instant scheduledStartAt;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "inferred_server_url", length = 512)
    private String inferredServerUrl;

    @Column(name = "linked_server_id")
    private Long linkedServerId;

    @Column(name = "raw_json", length = 4000)
    private String rawJson;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UpcomingServer() {
    }

    public UpcomingServer(String calendarId, String region, String worldName, String status) {
        this.calendarId = calendarId;
        this.region = region;
        this.worldName = worldName;
        this.status = status == null ? "UNKNOWN" : status;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.firstSeenAt == null) this.firstSeenAt = now;
        if (this.lastSeenAt == null) this.lastSeenAt = now;
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) this.status = "UNKNOWN";
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCalendarId() { return calendarId; }
    public String getRegion() { return region; }
    public String getWorldName() { return worldName; }
    public Integer getSpeed() { return speed; }
    public Instant getScheduledStartAt() { return scheduledStartAt; }
    public String getStatus() { return status; }
    public String getSourceUrl() { return sourceUrl; }
    public String getInferredServerUrl() { return inferredServerUrl; }
    public Long getLinkedServerId() { return linkedServerId; }
    public String getRawJson() { return rawJson; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setCalendarId(String calendarId) { this.calendarId = calendarId; }
    public void setRegion(String region) { this.region = region; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    public void setSpeed(Integer speed) { this.speed = speed; }
    public void setScheduledStartAt(Instant scheduledStartAt) { this.scheduledStartAt = scheduledStartAt; }
    public void setStatus(String status) { this.status = status == null ? "UNKNOWN" : status; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public void setInferredServerUrl(String inferredServerUrl) { this.inferredServerUrl = inferredServerUrl; }
    public void setLinkedServerId(Long linkedServerId) { this.linkedServerId = linkedServerId; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
