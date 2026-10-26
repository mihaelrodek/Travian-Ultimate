package com.travianbot.enemyintel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One scrape of one enemy player's public profile, append-only. Every
 * successful run of {@code EnemyProfileScraperService#scrape} writes a row
 * here; the row is never updated or deleted (except by account-deletion
 * cleanup).
 * <p>
 * The {@link EnemyPlayer} summary row holds the "latest" snapshot for fast
 * lookups; the snapshot table holds the full timeline for change detection,
 * the UI's "last 20 snapshots" list, and adversarial signals like "hero died
 * three times in a week".
 */
@Entity
@Table(
        name = "enemy_profile_snapshot",
        indexes = {
                @Index(name = "idx_enemy_snapshot_player_captured",
                        columnList = "enemy_player_id, captured_at")
        }
)
public class EnemyProfileSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enemy_player_id", nullable = false)
    private Long enemyPlayerId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "hero_level")
    private Integer heroLevel;

    @Column(name = "hero_xp")
    private Integer heroXp;

    @Column(name = "hero_production_bonus")
    private Integer heroProductionBonus;

    @Column(name = "current_population")
    private Integer currentPopulation;

    @Column(name = "village_count")
    private Integer villageCount;

    @Column(name = "alliance_travian_id")
    private Long allianceTravianId;

    @Column(name = "alliance_name", length = 100)
    private String allianceName;

    /** Human-readable diff vs the previous snapshot. Empty string when no meaningful change / first snapshot. */
    @Column(name = "diff_summary", length = 500)
    private String diffSummary;

    protected EnemyProfileSnapshot() {
    }

    public EnemyProfileSnapshot(Long enemyPlayerId, Instant capturedAt) {
        this.enemyPlayerId = enemyPlayerId;
        this.capturedAt = capturedAt;
    }

    public Long getId() { return id; }
    public Long getEnemyPlayerId() { return enemyPlayerId; }
    public Instant getCapturedAt() { return capturedAt; }
    public Integer getHeroLevel() { return heroLevel; }
    public Integer getHeroXp() { return heroXp; }
    public Integer getHeroProductionBonus() { return heroProductionBonus; }
    public Integer getCurrentPopulation() { return currentPopulation; }
    public Integer getVillageCount() { return villageCount; }
    public Long getAllianceTravianId() { return allianceTravianId; }
    public String getAllianceName() { return allianceName; }
    public String getDiffSummary() { return diffSummary; }

    public void setHeroLevel(Integer heroLevel) { this.heroLevel = heroLevel; }
    public void setHeroXp(Integer heroXp) { this.heroXp = heroXp; }
    public void setHeroProductionBonus(Integer heroProductionBonus) { this.heroProductionBonus = heroProductionBonus; }
    public void setCurrentPopulation(Integer currentPopulation) { this.currentPopulation = currentPopulation; }
    public void setVillageCount(Integer villageCount) { this.villageCount = villageCount; }
    public void setAllianceTravianId(Long allianceTravianId) { this.allianceTravianId = allianceTravianId; }
    public void setAllianceName(String allianceName) { this.allianceName = allianceName; }
    public void setDiffSummary(String diffSummary) {
        // Bound to the column's length cap so a verbose diff can't blow up the INSERT.
        if (diffSummary != null && diffSummary.length() > 500) {
            this.diffSummary = diffSummary.substring(0, 500);
        } else {
            this.diffSummary = diffSummary;
        }
    }

    /** True if every tracked field is null — happens when the profile page returned no parseable data. */
    public boolean isEmpty() {
        return heroLevel == null
                && heroXp == null
                && heroProductionBonus == null
                && currentPopulation == null
                && villageCount == null
                && allianceTravianId == null
                && (allianceName == null || allianceName.isBlank());
    }
}
