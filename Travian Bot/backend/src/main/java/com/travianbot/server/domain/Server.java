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
 * One Travian game world — a single {@code map.sql} endpoint that the intel
 * module can fetch and search against.
 * <p>
 * A {@code Server} is intentionally decoupled from {@code Account}: multiple
 * bot accounts can live on the same Travian server, and the inactive-search /
 * cropper-finder features only ever need the public {@code map.sql} payload
 * (no logged-in session). Scoping intel rows by {@code Server} lets the user
 * track ANY Travian world they care about, even ones they have no account on.
 * <p>
 * {@link #url} is the canonical key: the form stored on {@link
 * com.travianbot.account.domain.Account#getServerUrl()} with the trailing
 * slash stripped. The {@link #canonicalUrl(String)} helper applies the
 * normalisation so callers don't have to re-implement it.
 */
@Entity
@Table(
        name = "server",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_server_url",
                columnNames = "url"
        )
)
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 80)
    private String region;

    @Column(name = "game_world", length = 80)
    private String gameWorld;

    /**
     * Soft on/off switch. Inactive servers stay in the DB (we keep their
     * tracked map data) but are hidden from the picker and skipped by the
     * daily sync scheduler. Lets the user "park" a server they finished with
     * without losing history.
     */
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "last_map_synced_at")
    private Instant lastMapSyncedAt;

    /**
     * Snapshot of how many distinct players were upserted on the most recent
     * map.sql sync. Cached on the {@code Server} row so the picker can render
     * "12,345 players · synced 3 days ago" without re-counting the
     * {@code map_player} table on every list request.
     */
    @Column(name = "last_map_sync_player_count")
    private Integer lastMapSyncPlayerCount;

    /**
     * Server speed multiplier (1, 2, 3, 5, 10). Parsed from the canonical
     * Travian Legends URL pattern {@code ts{N}.x{SPEED}.{community}.travian.com}
     * by {@link com.travianbot.server.service.ServerService#parseSpeedFromUrl(String)}.
     * <p>
     * Nullable because legacy rows imported before this column existed have no
     * value — the {@code ServerSeeder} ApplicationRunner backfills them on boot
     * by re-parsing the URL.
     */
    @Column(name = "speed_multiplier")
    private Integer speedMultiplier;

    /**
     * Map half-width in fields. Defaults to 200 on regular worlds; the lone
     * x10 sprint world uses a 100-radius map. Nullable for the same reason as
     * {@link #speedMultiplier} — legacy rows get backfilled on boot.
     */
    @Column(name = "map_radius")
    private Integer mapRadius;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Server() {
    }

    public Server(String url, String name) {
        this.url = canonicalUrl(url);
        this.name = name;
        this.active = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Canonical form for a server URL: trimmed and with a single trailing
     * slash removed. Matches what {@code Account.serverUrl} stores in
     * practice and what {@link com.travianbot.intel.service.MapSqlFetcher}
     * expects.
     */
    public static String canonicalUrl(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        this.updatedAt = now;
        if (this.active == null) this.active = true;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUrl() { return url; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public String getGameWorld() { return gameWorld; }
    public Boolean getActive() { return active; }
    public Instant getLastMapSyncedAt() { return lastMapSyncedAt; }
    public Integer getLastMapSyncPlayerCount() { return lastMapSyncPlayerCount; }
    public Integer getSpeedMultiplier() { return speedMultiplier; }
    public Integer getMapRadius() { return mapRadius; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Persist a new url. Trims + strips trailing slash so equality with the
     * stored form is always exact — callers don't need to pre-normalize.
     */
    public void setUrl(String url) {
        this.url = canonicalUrl(url);
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public void setGameWorld(String gameWorld) {
        this.gameWorld = gameWorld;
    }

    public void setActive(Boolean active) {
        this.active = active == null ? Boolean.TRUE : active;
    }

    public void setLastMapSyncedAt(Instant lastMapSyncedAt) {
        this.lastMapSyncedAt = lastMapSyncedAt;
    }

    public void setLastMapSyncPlayerCount(Integer lastMapSyncPlayerCount) {
        this.lastMapSyncPlayerCount = lastMapSyncPlayerCount;
    }

    public void setSpeedMultiplier(Integer speedMultiplier) {
        this.speedMultiplier = speedMultiplier;
    }

    public void setMapRadius(Integer mapRadius) {
        this.mapRadius = mapRadius;
    }
}
