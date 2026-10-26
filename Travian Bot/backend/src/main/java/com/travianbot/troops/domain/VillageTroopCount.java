package com.travianbot.troops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Latest known troop counts stationed in a single village, populated from
 * the user's paste of {@code Village overview > Troops > Troops in villages}.
 * <p>
 * One row per village (unique on {@code village_id}) — the import overwrites
 * the previous snapshot rather than journaling, because the user manually
 * re-imports whenever they want fresh data and we don't need history yet.
 * <p>
 * Slots {@code t1..t10} are <em>per-tribe positional</em> — same convention
 * as the rally-point form: t1 is the first row in the tribe's troop list
 * (e.g. Phalanx for Gauls), t10 is the settler row. {@code hero} is the
 * eleventh column the in-game troops table renders for the Hero unit. The
 * paste's tribe is inferred from the header-row troop names; this entity
 * stays tribe-agnostic so we can render any account's data without
 * branching on tribe.
 * <p>
 * Foreign keys are intentionally raw {@code Long}s rather than JPA
 * relations — keeps the troops module independent of {@code village} schema
 * changes. Cleanup on account / village delete is handled via event
 * listeners (TODO Phase 0.3 — wire a cleanup listener if we later support
 * village deletion through the bot).
 */
@Entity
@Table(
        name = "village_troop_count",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_village_troop_count_village",
                columnNames = {"village_id"}
        )
)
public class VillageTroopCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "village_id", nullable = false)
    private Long villageId;

    /**
     * Tribe id (1..8) inferred from the paste's header row. {@code null} when
     * the parser couldn't match the header against any catalogued tribe —
     * the counts are still stored, the frontend just labels columns as
     * "t1..t10" instead of "Phalanx/Swordsman/...".
     */
    @Column(name = "tribe_id")
    private Integer tribeId;

    @Column(name = "t1", nullable = false) private int t1;
    @Column(name = "t2", nullable = false) private int t2;
    @Column(name = "t3", nullable = false) private int t3;
    @Column(name = "t4", nullable = false) private int t4;
    @Column(name = "t5", nullable = false) private int t5;
    @Column(name = "t6", nullable = false) private int t6;
    @Column(name = "t7", nullable = false) private int t7;
    @Column(name = "t8", nullable = false) private int t8;
    @Column(name = "t9", nullable = false) private int t9;
    @Column(name = "t10", nullable = false) private int t10;
    @Column(name = "hero", nullable = false) private int hero;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VillageTroopCount() {
    }

    public VillageTroopCount(Long accountId, Long villageId) {
        this.accountId = accountId;
        this.villageId = villageId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getVillageId() { return villageId; }
    public Integer getTribeId() { return tribeId; }
    public int getT1() { return t1; }
    public int getT2() { return t2; }
    public int getT3() { return t3; }
    public int getT4() { return t4; }
    public int getT5() { return t5; }
    public int getT6() { return t6; }
    public int getT7() { return t7; }
    public int getT8() { return t8; }
    public int getT9() { return t9; }
    public int getT10() { return t10; }
    public int getHero() { return hero; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTribeId(Integer tribeId) { this.tribeId = tribeId; }

    /**
     * Bulk update from a parsed row. Slot count is fixed at exactly 10
     * troop columns + 1 hero; anything else is a parser bug and will be
     * loud-failed (IllegalArgumentException) rather than silently truncated.
     */
    public void setCounts(int[] troops, int hero) {
        if (troops == null || troops.length != 10) {
            throw new IllegalArgumentException(
                    "VillageTroopCount expects exactly 10 troop slots, got "
                            + (troops == null ? "null" : troops.length));
        }
        this.t1 = troops[0];
        this.t2 = troops[1];
        this.t3 = troops[2];
        this.t4 = troops[3];
        this.t5 = troops[4];
        this.t6 = troops[5];
        this.t7 = troops[6];
        this.t8 = troops[7];
        this.t9 = troops[8];
        this.t10 = troops[9];
        this.hero = hero;
        this.updatedAt = Instant.now();
    }
}
