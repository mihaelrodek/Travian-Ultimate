package com.travianbot.enemyintel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;

/**
 * One enemy player we are tracking from the perspective of a single bot
 * account. Rows are per-(accountId, travianUid) — the same uid on two
 * different bot accounts (which would in practice mean two different Travian
 * servers) is two independent tracking rows. The latest known summary lives
 * here; the per-scrape append-only history sits in
 * {@link EnemyProfileSnapshot}.
 * <p>
 * The entity is created when an {@code IncomingAttack} (or any other module)
 * mentions a player name we can resolve against {@code MapPlayer}. From then
 * on the {@code EnemyIntelScheduler} keeps it fresh by periodically scraping
 * the public {@code /spieler.php?uid=…} profile page.
 * <p>
 * <b>Tracked fields</b> — the set of fields whose change qualifies as "this
 * row moved, stamp {@link #lastChangeAt}" inside
 * {@link #recordScrape(Integer, Integer, Integer, Integer, Integer, Long, String)}:
 * {@code heroLevel}, {@code heroXp}, {@code heroProductionBonus},
 * {@code currentPopulation}, {@code villageCount}, {@code allianceTravianId},
 * {@code allianceName}. We deliberately leave {@code tribeId} out — that
 * value never changes for a given uid, so a "change" there is a parser bug we
 * don't want to amplify.
 */
@Entity
@Table(
        name = "enemy_player",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_enemy_player_account_uid",
                columnNames = {"account_id", "travian_uid"}
        ),
        indexes = {
                @Index(name = "idx_enemy_player_account_last_scraped",
                        columnList = "account_id, last_scraped_at"),
                @Index(name = "idx_enemy_player_account_name",
                        columnList = "account_id, player_name")
        }
)
public class EnemyPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "travian_uid", nullable = false)
    private Long travianUid;

    @Column(name = "player_name", nullable = false, length = 100)
    private String playerName;

    @Column(name = "alliance_travian_id")
    private Long allianceTravianId;

    @Column(name = "alliance_name", length = 100)
    private String allianceName;

    @Column(name = "tribe_id")
    private Integer tribeId;

    @Column(name = "current_population")
    private Integer currentPopulation;

    @Column(name = "village_count")
    private Integer villageCount;

    /** Null = hero not visible on profile (locked / sitter-hidden / first scrape failed to parse). */
    @Column(name = "hero_level")
    private Integer heroLevel;

    @Column(name = "hero_xp")
    private Integer heroXp;

    /** Percent bonus from the hero panel (0..25 typically). Null until first parsed. */
    @Column(name = "hero_production_bonus")
    private Integer heroProductionBonus;

    /** Most recent {@code IncomingAttack#id} that referenced this player as the attacker. */
    @Column(name = "last_interaction_attack_id")
    private Long lastInteractionAttackId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_scraped_at", nullable = false)
    private Instant lastScrapedAt;

    /** When any tracked metric (see class javadoc) last differed from prior. Null until first change. */
    @Column(name = "last_change_at")
    private Instant lastChangeAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EnemyPlayer() {
    }

    /**
     * Creates a fresh tracking row. {@code lastScrapedAt} is seeded to
     * {@link #firstSeenAt} so the scheduler's "scrape oldest first" ORDER BY
     * works deterministically on row creation — without that seed, an
     * unscraped row would have a NULL value and be treated as either always-
     * due or never-due depending on the JPA dialect's NULL ordering.
     */
    public EnemyPlayer(Long accountId, Long travianUid, String playerName) {
        this.accountId = accountId;
        this.travianUid = travianUid;
        this.playerName = playerName;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastScrapedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getTravianUid() { return travianUid; }
    public String getPlayerName() { return playerName; }
    public Long getAllianceTravianId() { return allianceTravianId; }
    public String getAllianceName() { return allianceName; }
    public Integer getTribeId() { return tribeId; }
    public Integer getCurrentPopulation() { return currentPopulation; }
    public Integer getVillageCount() { return villageCount; }
    public Integer getHeroLevel() { return heroLevel; }
    public Integer getHeroXp() { return heroXp; }
    public Integer getHeroProductionBonus() { return heroProductionBonus; }
    public Long getLastInteractionAttackId() { return lastInteractionAttackId; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public Instant getLastChangeAt() { return lastChangeAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
        touch();
    }

    public void setTribeId(Integer tribeId) {
        this.tribeId = tribeId;
        touch();
    }

    /**
     * Set the most recent attack id that referenced this player as attacker.
     * Resolver-side call only — the scrape path doesn't know about attacks.
     */
    public void setLastInteractionAttackId(Long attackId) {
        this.lastInteractionAttackId = attackId;
        touch();
    }

    /**
     * Apply the freshly-parsed profile fields. Updates {@link #lastScrapedAt}
     * unconditionally; {@link #lastChangeAt} is only stamped when any of the
     * tracked fields (see class javadoc) actually differ from their prior
     * value. Tribe is updated as-is and does NOT participate in the change
     * detection — see the class javadoc for why.
     * <p>
     * Any null input means "we couldn't parse this field this time" and is
     * applied verbatim — callers that want to preserve a known-good prior
     * should pre-filter. This keeps the method honest: if the page hid the
     * hero level today, the row reflects that.
     */
    public void recordScrape(Integer heroLevel,
                             Integer heroXp,
                             Integer heroProductionBonus,
                             Integer currentPopulation,
                             Integer villageCount,
                             Long allianceTravianId,
                             String allianceName) {
        boolean changed =
                !Objects.equals(this.heroLevel, heroLevel)
                || !Objects.equals(this.heroXp, heroXp)
                || !Objects.equals(this.heroProductionBonus, heroProductionBonus)
                || !Objects.equals(this.currentPopulation, currentPopulation)
                || !Objects.equals(this.villageCount, villageCount)
                || !Objects.equals(this.allianceTravianId, allianceTravianId)
                || !Objects.equals(this.allianceName, allianceName);

        this.heroLevel = heroLevel;
        this.heroXp = heroXp;
        this.heroProductionBonus = heroProductionBonus;
        this.currentPopulation = currentPopulation;
        this.villageCount = villageCount;
        this.allianceTravianId = allianceTravianId;
        this.allianceName = allianceName;

        Instant now = Instant.now();
        this.lastScrapedAt = now;
        if (changed) {
            this.lastChangeAt = now;
        }
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
