package com.travianbot.farmlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single coordinate-with-troop-loadout entry in a {@link Farmlist}.
 * <p>
 * {@code troopsJson} stays a JSON string for now — troop composition varies per tribe
 * and this lets the frontend define the shape ({"t1": 50, "t4": 2} etc.) without
 * locking us into a schema before we know all the edge cases.
 * <p>
 * {@code travianTargetId} — when present, this is the target id used by the
 * rally-point "send"-checkbox form ({@code slot[X]} / {@code x} inputs). The
 * scraper fills it so the scheduler can issue a server-side send without
 * needing to parse the page again.
 */
@Entity
@Table(name = "farmlist_target")
public class FarmlistTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "farmlist_id", nullable = false)
    private Farmlist farmlist;

    @Column(length = 100)
    private String name;

    @Column(name = "coordinate_x", nullable = false)
    private int coordinateX;

    @Column(name = "coordinate_y", nullable = false)
    private int coordinateY;

    @Lob
    @Column(name = "troops_json", nullable = false, columnDefinition = "TEXT")
    private String troopsJson;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "travian_target_id")
    private Long travianTargetId;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    protected FarmlistTarget() {
    }

    public FarmlistTarget(Farmlist farmlist, String name, int x, int y, String troopsJson) {
        this.farmlist = farmlist;
        this.name = name;
        this.coordinateX = x;
        this.coordinateY = y;
        this.troopsJson = troopsJson;
        this.enabled = true;
    }

    public Long getId() { return id; }
    public Farmlist getFarmlist() { return farmlist; }
    public String getName() { return name; }
    public int getCoordinateX() { return coordinateX; }
    public int getCoordinateY() { return coordinateY; }
    public String getTroopsJson() { return troopsJson; }
    public boolean isEnabled() { return enabled; }
    public Long getTravianTargetId() { return travianTargetId; }
    public Instant getLastSentAt() { return lastSentAt; }

    public void update(String name, int x, int y, String troopsJson, boolean enabled) {
        this.name = name;
        this.coordinateX = x;
        this.coordinateY = y;
        this.troopsJson = troopsJson;
        this.enabled = enabled;
    }

    public void setTravianTargetId(Long travianTargetId) {
        this.travianTargetId = travianTargetId;
    }

    public void markSent() {
        this.lastSentAt = Instant.now();
    }
}
