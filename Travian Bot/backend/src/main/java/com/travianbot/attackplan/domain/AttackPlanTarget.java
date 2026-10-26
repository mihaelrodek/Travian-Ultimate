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

/**
 * One enemy village in an {@link AttackPlan}. We store coordinates rather
 * than a FK to a map-village row because the planner is usable BEFORE the
 * intel scan reaches that tile — the user often pastes raw coords from
 * Discord chat / forum posts during war prep.
 * <p>
 * {@link #label} is a free-text annotation the user keeps in their head
 * ("JohnDoe — Capital", "WW1") so the matrix table reads like the op brief
 * instead of a wall of coords.
 */
@Entity
@Table(name = "attack_plan_target")
public class AttackPlanTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private AttackPlan plan;

    @Column(name = "x", nullable = false)
    private int x;

    @Column(name = "y", nullable = false)
    private int y;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    public AttackPlanTarget() {
    }

    public AttackPlanTarget(AttackPlan plan, int x, int y, String label, int orderIndex) {
        this.plan = plan;
        this.x = x;
        this.y = y;
        this.label = label;
        this.orderIndex = orderIndex;
    }

    public Long getId() { return id; }
    public AttackPlan getPlan() { return plan; }
    public void setPlan(AttackPlan plan) { this.plan = plan; }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }

    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public String getLabel() { return label; }
    public void setLabel(String label) {
        this.label = (label == null || label.isBlank()) ? null : label.trim();
    }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
}
