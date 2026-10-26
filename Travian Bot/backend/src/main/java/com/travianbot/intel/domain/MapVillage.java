package com.travianbot.intel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One village row from a {@code map.sql} dump, scoped to a tracked Travian
 * server. Denormalises {@code playerUid} (no FK to {@link MapPlayer}) for
 * cheap bounding-box queries — the inactive-search service often needs "all
 * villages near my pivot, then group by player" without a join.
 */
@Entity
@Table(
        name = "map_village",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_map_village_server_vid",
                columnNames = {"server_id", "travian_vid"}
        ),
        indexes = {
                @Index(name = "idx_map_village_server_player",
                        columnList = "server_id, player_uid"),
                @Index(name = "idx_map_village_server_xy",
                        columnList = "server_id, x, y")
        }
)
public class MapVillage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "travian_vid", nullable = false)
    private Long travianVid;

    @Column(name = "player_uid", nullable = false)
    private Long playerUid;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer x;

    @Column(nullable = false)
    private Integer y;

    @Column(nullable = false)
    private Integer population;

    @Column(name = "is_capital", nullable = false)
    private Boolean isCapital = false;

    /**
     * Cropper classification bucket — one of {@code HIGH_15C}, {@code LIKELY_15C},
     * {@code POSSIBLE_15C}, {@code LIKELY_9C}, {@code UNKNOWN}, or {@code null}
     * for "not yet classified".
     * <p>
     * Stamped by {@code CropperClassifierService} after every map.sql sync — the
     * field is heuristic-only (population + capital flag + the player's village
     * count), <em>never</em> a ground-truth field-type. Nullable so existing
     * rows survive the {@code ddl-auto: update} migration that adds this
     * column. The parser does not populate this field; classification is a
     * separate, idempotent post-sync pass.
     */
    @Column(name = "cropper_classification", length = 20)
    private String cropperClassification;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MapVillage() {
    }

    public MapVillage(Long serverId, Long travianVid, Long playerUid,
                      String name, Integer x, Integer y, Integer population) {
        this.serverId = serverId;
        this.travianVid = travianVid;
        this.playerUid = playerUid;
        this.name = name;
        this.x = x;
        this.y = y;
        this.population = population;
        this.isCapital = false;
        Instant now = Instant.now();
        this.lastSeenAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getServerId() { return serverId; }
    public Long getTravianVid() { return travianVid; }
    public Long getPlayerUid() { return playerUid; }
    public String getName() { return name; }
    public Integer getX() { return x; }
    public Integer getY() { return y; }
    public Integer getPopulation() { return population; }
    public Boolean getIsCapital() { return isCapital; }
    public String getCropperClassification() { return cropperClassification; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) {
        this.name = name;
        touch();
    }

    public void setCoordinates(int x, int y) {
        this.x = x;
        this.y = y;
        touch();
    }

    public void setPopulation(Integer population) {
        this.population = population;
        touch();
    }

    public void setIsCapital(Boolean isCapital) {
        this.isCapital = isCapital;
        touch();
    }

    public void setPlayerUid(Long playerUid) {
        this.playerUid = playerUid;
        touch();
    }

    /**
     * Set by {@code CropperClassifierService} during the post-sync classification
     * pass. Callers other than that service should not call this — the field is
     * derived, not user-editable.
     */
    public void setCropperClassification(String cropperClassification) {
        this.cropperClassification = cropperClassification;
        touch();
    }

    public void markSeen(Instant at) {
        this.lastSeenAt = at;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
