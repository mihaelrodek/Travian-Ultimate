package com.travianbot.template.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One ordered step in a {@link VillageTemplate}. Semantically the same shape
 * as a {@code BuildingQueueItem} row: a building type to upgrade, a slot to
 * point at, and the target level we want it to reach.
 * <p>
 * Kept as a separate entity instead of JSON-blobbing on the parent because:
 * (1) CSV import/export is the headline feature — a normalised table makes
 *     row-by-row validation and conflict-free reordering trivial;
 * (2) a future migration that wants to slice template steps globally (e.g.
 *     "how many templates start with Main Building 20?") stays indexable.
 */
@Entity
@Table(name = "village_template_step")
public class TemplateStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private VillageTemplate template;

    @Column(nullable = false)
    private int orderIndex;

    @Column(nullable = false, length = 64)
    private String buildingType;

    /** Slot 1..40. Optional — templates may leave the slot unspecified. */
    @Column
    private Integer slot;

    @Column(nullable = false)
    private int targetLevel;

    protected TemplateStep() {
    }

    public TemplateStep(VillageTemplate template, int orderIndex, String buildingType, Integer slot, int targetLevel) {
        this.template = template;
        this.orderIndex = orderIndex;
        this.buildingType = buildingType;
        this.slot = slot;
        this.targetLevel = targetLevel;
    }

    public Long getId() { return id; }
    public VillageTemplate getTemplate() { return template; }
    public int getOrderIndex() { return orderIndex; }
    public String getBuildingType() { return buildingType; }
    public Integer getSlot() { return slot; }
    public int getTargetLevel() { return targetLevel; }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public void update(String buildingType, Integer slot, int targetLevel) {
        this.buildingType = buildingType;
        this.slot = slot;
        this.targetLevel = targetLevel;
    }
}
