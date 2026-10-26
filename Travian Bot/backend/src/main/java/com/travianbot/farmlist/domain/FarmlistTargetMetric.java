package com.travianbot.farmlist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Rolled-up performance metrics for a single {@link FarmlistTarget}.
 * <p>
 * The analyzer updates one row per target after every attributed
 * {@link RaidReport} so the analytics UI doesn't have to recompute aggregates
 * on every page load. There is exactly one metric row per target — enforced by
 * a unique constraint on {@code farmlist_target_id}.
 * <p>
 * Traffic-light rules (recomputed in
 * {@link com.travianbot.farmlist.service.FarmlistAnalyzerService} after each
 * ingest):
 * <ul>
 *   <li><b>RED</b> when {@code consecutiveLossRaids >= 3} OR
 *       ({@code totalOurTroopsLost > 0} AND {@code totalResourcesLooted < 5000}).
 *       The first clause catches "this target consistently kills our troops",
 *       the second catches "we've lost troops here and the gross gain doesn't
 *       even cover one tier-2 unit's recruitment cost".</li>
 *   <li><b>YELLOW</b> when {@code raidsWithLosses / totalRaids > 0.25}
 *       OR ({@code totalRaids >= 5} AND {@code totalResourcesLooted / totalRaids < 200}).
 *       Either we've taken losses too often, or the target is consistently
 *       too thin to be worth the carry capacity.</li>
 *   <li><b>GREEN</b> otherwise.</li>
 * </ul>
 * <p>
 * {@code prunCandidate} is asserted when the target is RED AND
 * {@code consecutiveLossRaids >= 3}. The UI highlights candidates so the user
 * can bulk-remove them from the parent farmlist.
 */
@Entity
@Table(
        name = "farmlist_target_metric",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_farmlist_target_metric_target",
                columnNames = "farmlist_target_id"
        )
)
public class FarmlistTargetMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farmlist_target_id", nullable = false, unique = true)
    private Long farmlistTargetId;

    @Column(name = "total_raids", nullable = false)
    private Integer totalRaids = 0;

    @Column(name = "raids_with_losses", nullable = false)
    private Integer raidsWithLosses = 0;

    /** Reset to 0 on any raid with no losses. Drives the auto-prune flag. */
    @Column(name = "consecutive_loss_raids", nullable = false)
    private Integer consecutiveLossRaids = 0;

    @Column(name = "total_resources_looted", nullable = false)
    private Long totalResourcesLooted = 0L;

    @Column(name = "total_carry_capacity_used", nullable = false)
    private Long totalCarryCapacityUsed = 0L;

    @Column(name = "total_our_troops_lost", nullable = false)
    private Integer totalOurTroopsLost = 0;

    @Column(name = "last_raid_at")
    private Instant lastRaidAt;

    /** Most recent raid where every troop returned at max load. */
    @Column(name = "last_full_haul_at")
    private Instant lastFullHaulAt;

    @Column(name = "traffic_light", nullable = false, length = 10)
    private String trafficLight = "GREEN";

    @Column(name = "prun_candidate", nullable = false)
    private Boolean prunCandidate = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FarmlistTargetMetric() {
    }

    public FarmlistTargetMetric(Long farmlistTargetId) {
        this.farmlistTargetId = farmlistTargetId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getFarmlistTargetId() { return farmlistTargetId; }
    public Integer getTotalRaids() { return totalRaids; }
    public Integer getRaidsWithLosses() { return raidsWithLosses; }
    public Integer getConsecutiveLossRaids() { return consecutiveLossRaids; }
    public Long getTotalResourcesLooted() { return totalResourcesLooted; }
    public Long getTotalCarryCapacityUsed() { return totalCarryCapacityUsed; }
    public Integer getTotalOurTroopsLost() { return totalOurTroopsLost; }
    public Instant getLastRaidAt() { return lastRaidAt; }
    public Instant getLastFullHaulAt() { return lastFullHaulAt; }
    public String getTrafficLight() { return trafficLight; }
    public Boolean getPrunCandidate() { return prunCandidate; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTotalRaids(Integer totalRaids) { this.totalRaids = totalRaids; }
    public void setRaidsWithLosses(Integer raidsWithLosses) { this.raidsWithLosses = raidsWithLosses; }
    public void setConsecutiveLossRaids(Integer consecutiveLossRaids) { this.consecutiveLossRaids = consecutiveLossRaids; }
    public void setTotalResourcesLooted(Long totalResourcesLooted) { this.totalResourcesLooted = totalResourcesLooted; }
    public void setTotalCarryCapacityUsed(Long totalCarryCapacityUsed) { this.totalCarryCapacityUsed = totalCarryCapacityUsed; }
    public void setTotalOurTroopsLost(Integer totalOurTroopsLost) { this.totalOurTroopsLost = totalOurTroopsLost; }
    public void setLastRaidAt(Instant lastRaidAt) { this.lastRaidAt = lastRaidAt; }
    public void setLastFullHaulAt(Instant lastFullHaulAt) { this.lastFullHaulAt = lastFullHaulAt; }
    public void setTrafficLight(String trafficLight) { this.trafficLight = trafficLight; }
    public void setPrunCandidate(Boolean prunCandidate) { this.prunCandidate = (prunCandidate != null) && prunCandidate; }
    public void touch() { this.updatedAt = Instant.now(); }
}
