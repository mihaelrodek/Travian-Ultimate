package com.travianbot.village.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A single resource tile outside a village — slots 1..18 on dorf1.php.
 * <p>
 * In T4.6 every village has exactly 18 resource fields. The usual distribution is
 * 4 wood + 4 clay + 4 iron + 6 crop (a "4-4-4-6"), but croppers (3-3-3-9, 1-1-1-15, …)
 * and other oases exist too. The composition is an artefact of the map and never changes,
 * but levels change as the player upgrades.
 */
@Entity
@Table(
        name = "resource_field",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_resource_field_village_slot",
                columnNames = {"village_id", "slot_id"}
        )
)
public class ResourceField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    /** 1..18 (dorf1 slot id). */
    @Column(name = "slot_id", nullable = false)
    private int slotId;

    /** Raw Travian gid — 1=Wood, 2=Clay, 3=Iron, 4=Crop. Kept alongside the enum for debugging. */
    @Column(nullable = false)
    private int gid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ResourceType type;

    @Column(nullable = false)
    private int level;

    @Column(name = "under_construction", nullable = false)
    private boolean underConstruction;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ResourceField() {
    }

    public ResourceField(Village village, int slotId, int gid, ResourceType type, int level) {
        this.village = village;
        this.slotId = slotId;
        this.gid = gid;
        this.type = type;
        this.level = level;
        this.underConstruction = false;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public int getSlotId() { return slotId; }
    public int getGid() { return gid; }
    public ResourceType getType() { return type; }
    public int getLevel() { return level; }
    public boolean isUnderConstruction() { return underConstruction; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Refreshes mutable fields from a fresh scrape; preserves id + creation. */
    public void refresh(int gid, ResourceType type, int level, boolean underConstruction) {
        this.gid = gid;
        this.type = type;
        this.level = level;
        this.underConstruction = underConstruction;
        this.updatedAt = Instant.now();
    }
}
