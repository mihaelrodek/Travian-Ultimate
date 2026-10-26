package com.travianbot.attackplan.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A named war operation — a list of enemy targets and the friendly source
 * villages that will fire at them. The matrix endpoint computes per-source ×
 * per-target travel times, which the UI renders as a grid the user can sort
 * by launch time.
 * <p>
 * Why an entity (not a one-shot calc)? Players iterate on these for hours
 * before an op — add a target, swap a source out, retime, share with allies.
 * Persisting the whole plan makes that workflow cheap and means a browser
 * refresh doesn't drop the work.
 * <p>
 * <strong>Speed model.</strong> {@link #speedFieldsPerHour} is the base
 * fields/hour for the unit the user will send (e.g. 14 for Teutonic Knight,
 * 19 for Theutates Thunder). The matrix calculator multiplies by the
 * canonical {@code Server.speedMultiplier} for the account's server, so the
 * stored value is always the x1 base — the user doesn't have to remember
 * which speed server they're on.
 */
@Entity
@Table(name = "attack_plan")
public class AttackPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * Optional UTC instant the troops should LAND on the target. When set, the
     * matrix calculator derives a per-source launch time. Null means "no
     * landing constraint yet" — the matrix still shows travel times, just no
     * launch column.
     */
    @Column(name = "landing_at")
    private Instant landingAt;

    /**
     * Base unit speed in fields-per-hour (server-speed x1). User-typed —
     * defaults to 14 (Teutonic Knight, a common offensive baseline) when a
     * plan is created via the UI. Required so the matrix calculation is
     * always defined.
     */
    @Column(name = "speed_fields_per_hour", nullable = false)
    private Double speedFieldsPerHour = 14.0;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC, id ASC")
    private List<AttackPlanTarget> targets = new ArrayList<>();

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC, id ASC")
    private List<AttackPlanSource> sources = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public AttackPlan() {
    }

    public AttackPlan(Long accountId, String name) {
        this.accountId = accountId;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; touch(); }

    public Instant getLandingAt() { return landingAt; }
    public void setLandingAt(Instant landingAt) { this.landingAt = landingAt; touch(); }

    public Double getSpeedFieldsPerHour() { return speedFieldsPerHour; }
    public void setSpeedFieldsPerHour(Double speedFieldsPerHour) {
        this.speedFieldsPerHour = speedFieldsPerHour;
        touch();
    }

    public List<AttackPlanTarget> getTargets() { return targets; }
    public List<AttackPlanSource> getSources() { return sources; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
