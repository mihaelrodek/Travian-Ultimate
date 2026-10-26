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
 * One in-game player as observed via the daily {@code map.sql} dump for a given
 * {@code serverId}. Rows are per-(server, travianUid) — the map.sql we fetch
 * is server-scoped, so two different Travian servers tracked in this bot will
 * see different player populations even if the uid happens to collide
 * numerically.
 * <p>
 * Population history is kept compactly as a current value plus two snapshots
 * (7d / 30d). That is plenty for the inactive-detection heuristic and avoids
 * a per-day history table that would balloon for an old server. The
 * {@link #recordSnapshot(int, int, Instant)} method only stamps
 * {@code lastPopChangeAt} when the value actually moved, so a player that
 * sits unchanged for weeks naturally drifts past the inactivity threshold.
 */
@Entity
@Table(
        name = "map_player",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_map_player_server_uid",
                columnNames = {"server_id", "travian_uid"}
        ),
        indexes = {
                @Index(name = "idx_map_player_server_inactive",
                        columnList = "server_id, is_inactive"),
                @Index(name = "idx_map_player_server_alliance",
                        columnList = "server_id, alliance_name")
        }
)
public class MapPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "travian_uid", nullable = false)
    private Long travianUid;

    @Column(name = "player_name", nullable = false, length = 100)
    private String playerName;

    @Column(name = "travian_aid")
    private Long travianAid;

    @Column(name = "alliance_name", length = 100)
    private String allianceName;

    /** 1=Romans, 2=Teutons, 3=Gauls, 4=Nature (skipped before insert), 5=Natars, 6=Egyptians, 7=Huns. */
    @Column(name = "tribe_id", nullable = false)
    private Integer tribeId;

    @Column(name = "current_population", nullable = false)
    private Integer currentPopulation;

    @Column(name = "village_count", nullable = false)
    private Integer villageCount;

    /** Snapshot of {@link #currentPopulation} as it was ~7 days ago. */
    @Column(name = "population_snapshot_7d_ago")
    private Integer populationSnapshot7dAgo;

    /** Snapshot of {@link #currentPopulation} as it was ~30 days ago. */
    @Column(name = "population_snapshot_30d_ago")
    private Integer populationSnapshot30dAgo;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Last time the population value changed (any direction). Null until first change. */
    @Column(name = "last_pop_change_at")
    private Instant lastPopChangeAt;

    @Column(name = "is_inactive", nullable = false)
    private Boolean isInactive = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MapPlayer() {
    }

    public MapPlayer(Long serverId, Long travianUid, String playerName,
                     Integer tribeId, Integer currentPopulation, Integer villageCount) {
        this.serverId = serverId;
        this.travianUid = travianUid;
        this.playerName = playerName;
        this.tribeId = tribeId;
        this.currentPopulation = currentPopulation;
        this.villageCount = villageCount;
        this.isInactive = false;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getServerId() { return serverId; }
    public Long getTravianUid() { return travianUid; }
    public String getPlayerName() { return playerName; }
    public Long getTravianAid() { return travianAid; }
    public String getAllianceName() { return allianceName; }
    public Integer getTribeId() { return tribeId; }
    public Integer getCurrentPopulation() { return currentPopulation; }
    public Integer getVillageCount() { return villageCount; }
    public Integer getPopulationSnapshot7dAgo() { return populationSnapshot7dAgo; }
    public Integer getPopulationSnapshot30dAgo() { return populationSnapshot30dAgo; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getLastPopChangeAt() { return lastPopChangeAt; }
    public Boolean getIsInactive() { return isInactive; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Refresh alliance attribution — both fields move together so callers can't desync them. */
    public void setAlliance(Long travianAid, String allianceName) {
        this.travianAid = travianAid;
        this.allianceName = allianceName;
        touch();
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
        touch();
    }

    public void setTribeId(Integer tribeId) {
        this.tribeId = tribeId;
        touch();
    }

    public void setPopulationSnapshot7dAgo(Integer value) {
        this.populationSnapshot7dAgo = value;
        touch();
    }

    public void setPopulationSnapshot30dAgo(Integer value) {
        this.populationSnapshot30dAgo = value;
        touch();
    }

    public void setIsInactive(Boolean isInactive) {
        this.isInactive = isInactive;
        touch();
    }

    /**
     * Apply a fresh observation from a map.sql sync. Updates current state and
     * stamps {@link #lastSeenAt} unconditionally; {@link #lastPopChangeAt} is
     * only stamped when {@code pop} differs from the prior value. That lets the
     * inactivity heuristic distinguish "we keep seeing this row" from "this row
     * is actually moving" — the latter is the signal we care about.
     */
    public void recordSnapshot(int pop, int villages, Instant at) {
        if (this.currentPopulation == null || this.currentPopulation != pop) {
            this.lastPopChangeAt = at;
        }
        this.currentPopulation = pop;
        this.villageCount = villages;
        this.lastSeenAt = at;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
