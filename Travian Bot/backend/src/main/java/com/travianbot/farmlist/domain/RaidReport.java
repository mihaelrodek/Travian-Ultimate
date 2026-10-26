package com.travianbot.farmlist.domain;

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
 * One row per raid that came back with a parseable battle report.
 * <p>
 * Travian assigns its own internal id to each report (the {@code id=N} in
 * {@code berichte.php?id=N}); we persist that under {@code travianReportId}
 * and use it together with {@code accountId} as the dedup key — the scraper
 * walks the report list every tick and refuses to insert a row twice for the
 * same (account, travianReportId).
 * <p>
 * The {@code farmlistTargetId} is attributed by coordinate match after the
 * report is parsed — left null when nothing in the local farmlists matches
 * (e.g. one-off raids the user fired manually, lists already deleted).
 * The {@code farmlistId} captures which list the row was originally
 * associated with so that analytics can still summarise by list even after
 * a target is removed.
 * <p>
 * No FK constraints — the analyzer is loosely coupled. Deleting a farmlist
 * does not cascade-delete reports; the rows are kept as historic data and
 * the analytics tab simply stops listing them as attributable.
 */
@Entity
@Table(
        name = "raid_report",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_raid_report_account_travianid",
                columnNames = {"account_id", "travian_report_id"}
        ),
        indexes = {
                @Index(name = "ix_raid_report_target", columnList = "farmlist_target_id"),
                @Index(name = "ix_raid_report_account_returnedAt",
                        columnList = "account_id, raid_returned_at")
        }
)
public class RaidReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "farmlist_id", nullable = false)
    private Long farmlistId;

    /** Null when the coordinate doesn't match any known farmlist target. */
    @Column(name = "farmlist_target_id")
    private Long farmlistTargetId;

    @Column(name = "travian_report_id", nullable = false)
    private Long travianReportId;

    /** Best-effort — null when we don't know when our wave was launched. */
    @Column(name = "raid_launched_at")
    private Instant raidLaunchedAt;

    @Column(name = "raid_returned_at", nullable = false)
    private Instant raidReturnedAt;

    @Column(name = "target_x", nullable = false)
    private Integer targetX;

    @Column(name = "target_y", nullable = false)
    private Integer targetY;

    @Column(name = "target_village_name", length = 200)
    private String targetVillageName;

    @Column(name = "wood_looted", nullable = false)
    private Integer woodLooted = 0;

    @Column(name = "clay_looted", nullable = false)
    private Integer clayLooted = 0;

    @Column(name = "iron_looted", nullable = false)
    private Integer ironLooted = 0;

    @Column(name = "crop_looted", nullable = false)
    private Integer cropLooted = 0;

    /** Sum across all troops in the wave. Null when not derivable from the page. */
    @Column(name = "carry_capacity_total")
    private Integer carryCapacityTotal;

    /** True iff every troop returned at max load — implies target had more loot. */
    @Column(name = "was_full_haul", nullable = false)
    private Boolean wasFullHaul = false;

    @Column(name = "our_troops_lost", nullable = false)
    private Integer ourTroopsLost = 0;

    @Column(name = "enemy_troops_lost", nullable = false)
    private Integer enemyTroopsLost = 0;

    /** One of: VICTORY_NO_LOSS, VICTORY_WITH_LOSS, DEFEAT, UNKNOWN. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome = "UNKNOWN";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RaidReport() {
    }

    public RaidReport(Long accountId, Long farmlistId, Long travianReportId,
                      Integer targetX, Integer targetY, Instant raidReturnedAt) {
        this.accountId = accountId;
        this.farmlistId = farmlistId;
        this.travianReportId = travianReportId;
        this.targetX = targetX;
        this.targetY = targetY;
        this.raidReturnedAt = (raidReturnedAt != null) ? raidReturnedAt : Instant.now();
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getFarmlistId() { return farmlistId; }
    public Long getFarmlistTargetId() { return farmlistTargetId; }
    public Long getTravianReportId() { return travianReportId; }
    public Instant getRaidLaunchedAt() { return raidLaunchedAt; }
    public Instant getRaidReturnedAt() { return raidReturnedAt; }
    public Integer getTargetX() { return targetX; }
    public Integer getTargetY() { return targetY; }
    public String getTargetVillageName() { return targetVillageName; }
    public Integer getWoodLooted() { return woodLooted; }
    public Integer getClayLooted() { return clayLooted; }
    public Integer getIronLooted() { return ironLooted; }
    public Integer getCropLooted() { return cropLooted; }
    public Integer getCarryCapacityTotal() { return carryCapacityTotal; }
    public Boolean getWasFullHaul() { return wasFullHaul; }
    public Integer getOurTroopsLost() { return ourTroopsLost; }
    public Integer getEnemyTroopsLost() { return enemyTroopsLost; }
    public String getOutcome() { return outcome; }
    public Instant getCreatedAt() { return createdAt; }

    public void setFarmlistTargetId(Long farmlistTargetId) { this.farmlistTargetId = farmlistTargetId; }
    public void setRaidLaunchedAt(Instant raidLaunchedAt) { this.raidLaunchedAt = raidLaunchedAt; }
    public void setTargetVillageName(String targetVillageName) { this.targetVillageName = targetVillageName; }

    /** Total resources looted across the four types — convenience for the analyzer. */
    public long totalLooted() {
        return safe(woodLooted) + safe(clayLooted) + safe(ironLooted) + safe(cropLooted);
    }

    /**
     * Sets the loot quartet in one call. Null entries become 0 — the schema
     * column is NOT NULL with a default of 0 and the analyzer aggregates as
     * a sum, so null defaulting is the only safe behaviour.
     */
    public void setLoot(Integer wood, Integer clay, Integer iron, Integer crop) {
        this.woodLooted = (wood == null) ? 0 : wood;
        this.clayLooted = (clay == null) ? 0 : clay;
        this.ironLooted = (iron == null) ? 0 : iron;
        this.cropLooted = (crop == null) ? 0 : crop;
    }

    public void setCarryCapacityTotal(Integer carryCapacityTotal) { this.carryCapacityTotal = carryCapacityTotal; }
    public void setWasFullHaul(Boolean wasFullHaul) { this.wasFullHaul = (wasFullHaul != null) && wasFullHaul; }
    public void setOurTroopsLost(Integer ourTroopsLost) { this.ourTroopsLost = (ourTroopsLost == null) ? 0 : ourTroopsLost; }
    public void setEnemyTroopsLost(Integer enemyTroopsLost) { this.enemyTroopsLost = (enemyTroopsLost == null) ? 0 : enemyTroopsLost; }
    public void setOutcome(String outcome) { this.outcome = (outcome == null) ? "UNKNOWN" : outcome; }

    private static int safe(Integer v) { return v == null ? 0 : v; }
}
