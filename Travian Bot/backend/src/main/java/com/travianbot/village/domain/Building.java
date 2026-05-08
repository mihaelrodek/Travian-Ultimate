package com.travianbot.village.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A building slot inside a village — slots 19..40 on dorf2.php.
 * <p>
 * Unlike {@link ResourceField}, buildings can be empty. An empty slot has {@code gid == 0}
 * and {@code level == 0}. Slots 39 (rally point) and 40 (wall) are always the same type
 * across every village but are tracked the same way for consistency.
 * <p>
 * The gid → building name mapping is a static catalogue maintained in code
 * (see {@code BuildingCatalog}) — keeping it out of the DB lets us evolve the
 * catalogue without migrations.
 */
@Entity
@Table(
        name = "building",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_building_village_slot",
                columnNames = {"village_id", "slot_id"}
        )
)
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    /** 19..40 (dorf2 slot id). */
    @Column(name = "slot_id", nullable = false)
    private int slotId;

    /** Travian gid; 0 = empty. */
    @Column(nullable = false)
    private int gid;

    /** Cached display name (looked up from the catalog at scrape time). Nullable for empty slots. */
    @Column(length = 64)
    private String name;

    @Column(nullable = false)
    private int level;

    @Column(name = "under_construction", nullable = false)
    private boolean underConstruction;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Building() {
    }

    public Building(Village village, int slotId, int gid, String name, int level) {
        this.village = village;
        this.slotId = slotId;
        this.gid = gid;
        this.name = name;
        this.level = level;
        this.underConstruction = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public int getSlotId() { return slotId; }
    public int getGid() { return gid; }
    public String getName() { return name; }
    public int getLevel() { return level; }
    public boolean isUnderConstruction() { return underConstruction; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isEmpty() { return gid == 0; }

    public void refresh(int gid, String name, int level, boolean underConstruction) {
        this.gid = gid;
        this.name = name;
        this.level = level;
        this.underConstruction = underConstruction;
        this.updatedAt = Instant.now();
    }
}
