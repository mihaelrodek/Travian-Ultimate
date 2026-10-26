package com.travianbot.attackplan.domain;

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

/**
 * One friendly village taking part in an {@link AttackPlan}. The unit + speed
 * are NOT stored here — the plan carries a single {@code speedFieldsPerHour}
 * that applies to every source. A future iteration can promote this to a
 * per-source override (different villages send different hammers) by adding
 * nullable {@code unitTribeId / unitTIndex} columns; absent fields would
 * then fall back to the plan's default.
 */
@Entity
@Table(name = "attack_plan_source",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attack_plan_source",
                columnNames = {"plan_id", "village_id"}
        ))
public class AttackPlanSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private AttackPlan plan;

    /**
     * FK to {@code village.id} — kept as a raw long instead of a
     * {@code @ManyToOne Village} so deleting a village doesn't cascade-cascade
     * through every plan that ever referenced it. The service layer prunes
     * orphan source rows lazily during matrix calc instead.
     */
    @Column(name = "village_id", nullable = false)
    private Long villageId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public AttackPlanSource() {
    }

    public AttackPlanSource(AttackPlan plan, Long villageId, int orderIndex) {
        this.plan = plan;
        this.villageId = villageId;
        this.orderIndex = orderIndex;
    }

    public Long getId() { return id; }
    public AttackPlan getPlan() { return plan; }
    public void setPlan(AttackPlan plan) { this.plan = plan; }

    public Long getVillageId() { return villageId; }
    public void setVillageId(Long villageId) { this.villageId = villageId; }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
}
