package com.travianbot.intel.alliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One in-game alliance as observed via the daily {@code map.sql} dump for a
 * given {@code serverId}.
 * <p>
 * The aggregated columns ({@link #memberCount}, {@link #totalPopulation},
 * {@link #averagePopulation}) are derived from the current set of
 * {@code MapPlayer} rows on the same server, refreshed every sync via
 * {@code MapAllianceAggregator}. The detail columns ({@link #description},
 * the bonus fields, {@link #rawBonusesJson}) are filled in on-demand by
 * {@code AllianceDetailScraperService} when the user clicks "scrape detail"
 * in the UI — they are nullable until the first detail scrape.
 * <p>
 * Disbanded alliances are kept around with {@code memberCount = 0} so the
 * cached description / bonuses survive — useful when the user wants to look
 * back at "who was in alliance X six months ago". The sync only ever zeros
 * the counts, never deletes the row.
 */
@Entity
@Table(
        name = "map_alliance",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_map_alliance_server_aid",
                columnNames = {"server_id", "travian_aid"}
        ),
        indexes = {
                @Index(name = "idx_map_alliance_server_name",
                        columnList = "server_id, name"),
                @Index(name = "idx_map_alliance_server_members",
                        columnList = "server_id, member_count")
        }
)
public class MapAlliance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "travian_aid", nullable = false)
    private Long travianAid;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "member_count", nullable = false)
    private Integer memberCount = 0;

    @Column(name = "total_population", nullable = false)
    private Long totalPopulation = 0L;

    @Column(name = "average_population", nullable = false)
    private Integer averagePopulation = 0;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Last time the on-demand alliance-page scraper ran. Null = never scraped. */
    @Column(name = "last_detail_scraped_at")
    private Instant lastDetailScrapedAt;

    @Column(length = 2000)
    private String description;

    @Column(name = "bonus_diplomacy")
    private Integer bonusDiplomacy;

    @Column(name = "bonus_mining")
    private Integer bonusMining;

    @Column(name = "bonus_scout")
    private Integer bonusScout;

    @Column(name = "bonus_brewery")
    private Integer bonusBrewery;

    /**
     * Full bonuses table serialized as a JSON object (name → level). Captures
     * skin-specific bonuses we don't model as first-class fields so a future
     * migration can promote them without losing the historical reading.
     */
    @Column(name = "raw_bonuses_json", length = 2000)
    private String rawBonusesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MapAlliance() {
    }

    public MapAlliance(Long serverId, Long travianAid, String name,
                       Integer memberCount, Long totalPopulation, Integer averagePopulation,
                       Instant firstSeenAt, Instant lastSeenAt) {
        this.serverId = serverId;
        this.travianAid = travianAid;
        this.name = name;
        this.memberCount = memberCount == null ? 0 : memberCount;
        this.totalPopulation = totalPopulation == null ? 0L : totalPopulation;
        this.averagePopulation = averagePopulation == null ? 0 : averagePopulation;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.firstSeenAt == null) this.firstSeenAt = now;
        if (this.lastSeenAt == null) this.lastSeenAt = now;
        this.updatedAt = now;
        if (this.memberCount == null) this.memberCount = 0;
        if (this.totalPopulation == null) this.totalPopulation = 0L;
        if (this.averagePopulation == null) this.averagePopulation = 0;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getServerId() { return serverId; }
    public Long getTravianAid() { return travianAid; }
    public String getName() { return name; }
    public Integer getMemberCount() { return memberCount; }
    public Long getTotalPopulation() { return totalPopulation; }
    public Integer getAveragePopulation() { return averagePopulation; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getLastDetailScrapedAt() { return lastDetailScrapedAt; }
    public String getDescription() { return description; }
    public Integer getBonusDiplomacy() { return bonusDiplomacy; }
    public Integer getBonusMining() { return bonusMining; }
    public Integer getBonusScout() { return bonusScout; }
    public Integer getBonusBrewery() { return bonusBrewery; }
    public String getRawBonusesJson() { return rawBonusesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setMemberCount(Integer memberCount) { this.memberCount = memberCount == null ? 0 : memberCount; }
    public void setTotalPopulation(Long totalPopulation) { this.totalPopulation = totalPopulation == null ? 0L : totalPopulation; }
    public void setAveragePopulation(Integer averagePopulation) { this.averagePopulation = averagePopulation == null ? 0 : averagePopulation; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public void setLastDetailScrapedAt(Instant lastDetailScrapedAt) { this.lastDetailScrapedAt = lastDetailScrapedAt; }
    public void setDescription(String description) { this.description = description; }
    public void setBonusDiplomacy(Integer bonusDiplomacy) { this.bonusDiplomacy = bonusDiplomacy; }
    public void setBonusMining(Integer bonusMining) { this.bonusMining = bonusMining; }
    public void setBonusScout(Integer bonusScout) { this.bonusScout = bonusScout; }
    public void setBonusBrewery(Integer bonusBrewery) { this.bonusBrewery = bonusBrewery; }
    public void setRawBonusesJson(String rawBonusesJson) { this.rawBonusesJson = rawBonusesJson; }

    /**
     * Apply a fresh map.sql aggregation snapshot. Recomputes the average so
     * callers can't desync it from total/count. The {@code lastSeenAt} stamp
     * is refreshed every sync — used by the UI to render "last seen N days
     * ago" on the alliance list, distinct from {@link #lastDetailScrapedAt}.
     */
    public void recordSnapshot(int memberCount, long totalPopulation, Instant at) {
        this.memberCount = memberCount;
        this.totalPopulation = totalPopulation;
        this.averagePopulation = memberCount > 0
                ? (int) Math.round((double) totalPopulation / memberCount)
                : 0;
        this.lastSeenAt = at;
    }

    /**
     * Apply a fresh on-demand detail scrape. Bonus fields are passed through
     * untouched — {@code null} means "the parser didn't find this bonus on
     * the page" and overwrites any prior reading (defensive: a bonus we
     * could read last week but can't read today is more likely a parsing bug
     * than a real change, but we'd rather show "unknown" than stale data).
     */
    public void recordDetail(String description,
                             Integer bonusDiplomacy, Integer bonusMining,
                             Integer bonusScout, Integer bonusBrewery,
                             String rawBonusesJson, Instant at) {
        this.description = description;
        this.bonusDiplomacy = bonusDiplomacy;
        this.bonusMining = bonusMining;
        this.bonusScout = bonusScout;
        this.bonusBrewery = bonusBrewery;
        this.rawBonusesJson = rawBonusesJson;
        this.lastDetailScrapedAt = at;
    }
}
