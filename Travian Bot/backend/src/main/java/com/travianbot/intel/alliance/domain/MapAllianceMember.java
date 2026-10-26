package com.travianbot.intel.alliance.domain;

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
 * One member of an alliance, as observed by an on-demand scrape of
 * {@code /allianz.php?aid=N}.
 * <p>
 * Loose-coupled to {@link MapAlliance} by id — no JPA relation — to keep
 * cascades explicit and avoid pulling the full roster every time the
 * alliance itself is loaded. Roster rows are <strong>replaced wholesale</strong>
 * on each successful detail scrape: delete-all-by-alliance, then insert the
 * new set. That's cheap (rosters cap at ~60 rows) and avoids per-row diff
 * logic.
 * <p>
 * The {@link #role} field is the raw label from the page ("Leader", "Side
 * leader", "Member", "Diplomat", localized variants) — we keep it
 * untranslated so the UI can decide how to render or normalise it later.
 */
@Entity
@Table(
        name = "map_alliance_member",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_map_alliance_member_alliance_uid",
                columnNames = {"map_alliance_id", "travian_uid"}
        ),
        indexes = {
                @Index(name = "idx_map_alliance_member_alliance",
                        columnList = "map_alliance_id")
        }
)
public class MapAllianceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "map_alliance_id", nullable = false)
    private Long mapAllianceId;

    @Column(name = "travian_uid", nullable = false)
    private Long travianUid;

    @Column(name = "player_name", nullable = false, length = 100)
    private String playerName;

    @Column(nullable = false)
    private Integer population;

    @Column(length = 50)
    private String role;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected MapAllianceMember() {
    }

    public MapAllianceMember(Long mapAllianceId, Long travianUid, String playerName,
                             Integer population, String role, Instant capturedAt) {
        this.mapAllianceId = mapAllianceId;
        this.travianUid = travianUid;
        this.playerName = playerName;
        this.population = population;
        this.role = role;
        this.capturedAt = capturedAt;
    }

    public Long getId() { return id; }
    public Long getMapAllianceId() { return mapAllianceId; }
    public Long getTravianUid() { return travianUid; }
    public String getPlayerName() { return playerName; }
    public Integer getPopulation() { return population; }
    public String getRole() { return role; }
    public Instant getCapturedAt() { return capturedAt; }
}
