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

/**
 * One row per (village, troop) the user wants the bot to keep training. The
 * scheduler picks a uniform random quantity in
 * [{@code minQty}, {@code maxQty}] each cycle and queues that many units in
 * the corresponding training building (Barracks / Stable / Workshop) — so
 * the bot's training pattern doesn't look mechanical.
 * <p>
 * Stored as an explicit table rather than a JSON blob on
 * {@link VillageSettings} because:
 *   - the catalogue is tribe-dependent (different troopCodes for different
 *     tribes) and a relational shape lets us enforce uniqueness per
 *     (village, troop) without app-level checks;
 *   - the scheduler will eventually want to query "which villages train
 *     {@code legionnaires} right now" — much easier with a column index than
 *     a JSON path lookup on every poll.
 * <p>
 * Cascade-on-village-delete is wired explicitly through
 * {@code VillageService.delete} (no JPA cascade) so the same delete path is
 * the only one that has to know about it.
 */
@Entity
@Table(
        name = "village_train_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_train_cfg_village_troop",
                columnNames = {"village_id", "troop_code"}
        )
)
public class VillageTrainConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "village_id", nullable = false)
    private Village village;

    /**
     * Troop code from the tribe-scoped catalogue (e.g. {@code LEGIONNAIRE},
     * {@code PRAETORIAN}). Free-form string by design — the catalogue grows
     * faster than we'd want to maintain an enum migration for.
     */
    @Column(name = "troop_code", nullable = false, length = 64)
    private String troopCode;

    @Column(name = "min_qty", nullable = false)
    private int minQty;

    @Column(name = "max_qty", nullable = false)
    private int maxQty;

    protected VillageTrainConfig() {
    }

    public VillageTrainConfig(Village village, String troopCode, int minQty, int maxQty) {
        this.village = village;
        this.troopCode = troopCode;
        this.minQty = minQty;
        this.maxQty = maxQty;
    }

    public Long getId() { return id; }
    public Village getVillage() { return village; }
    public String getTroopCode() { return troopCode; }
    public int getMinQty() { return minQty; }
    public int getMaxQty() { return maxQty; }

    public void update(int minQty, int maxQty) {
        this.minQty = minQty;
        this.maxQty = maxQty;
    }
}
