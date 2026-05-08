package com.travianbot.hero.domain;

import com.travianbot.account.domain.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The one-and-only hero per {@link Account}. Scraped from the hero/attributes,
 * hero/inventory and hero/adventures pages and refreshed on demand.
 * <p>
 * Most fields are nullable because the scraper may only have one of the three
 * sub-pages (e.g. attributes but not inventory) when the page layout shifts.
 * The entity is optimistic about missing data — we store what we got and let
 * the UI render "—" for unknowns rather than refusing to load.
 */
@Entity
@Table(name = "hero")
public class Hero {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    /** Hero's in-game name (shown at the top of the attributes page). Nullable before first scrape. */
    @Column(length = 100)
    private String name;

    @Column(nullable = false)
    private int level = 0;

    /** Total XP accumulated at current level. */
    @Column(nullable = false)
    private long experience = 0;

    /** XP threshold for the next level (if the page shows it). Nullable on older skins. */
    @Column(name = "experience_for_next")
    private Long experienceForNext;

    /** Current health, 0..100. */
    @Column(nullable = false)
    private int health = 0;

    /** Regeneration per hour in percent — higher when Helmet of Regeneration is equipped. */
    @Column(name = "regen_per_hour", nullable = false)
    private int regenPerHour = 0;

    /** Free (unspent) skill points. */
    @Column(name = "free_points", nullable = false)
    private int freePoints = 0;

    /** Points spent in the four categories. Max 100 each. */
    @Column(name = "points_fighting_strength", nullable = false)
    private int pointsFightingStrength = 0;

    @Column(name = "points_off_bonus", nullable = false)
    private int pointsOffBonus = 0;

    @Column(name = "points_def_bonus", nullable = false)
    private int pointsDefBonus = 0;

    @Column(name = "points_resources", nullable = false)
    private int pointsResources = 0;

    /** Number of unused adventures in the list. */
    @Column(name = "adventure_points", nullable = false)
    private int adventurePoints = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HeroStatus status = HeroStatus.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "production_type", nullable = false, length = 16)
    private HeroProductionType productionType = HeroProductionType.UNKNOWN;

    /** Travian did of the village where hero's production bonus is applied. */
    @Column(name = "home_village_did")
    private Long homeVillageDid;

    /** Travian did of the village the hero is currently standing in. */
    @Column(name = "current_village_did")
    private Long currentVillageDid;

    /** If {@link #status} is {@code DEAD}/{@code REVIVING}, when the hero returns. */
    @Column(name = "revive_at")
    private Instant reviveAt;

    @Column(name = "last_scraped_at")
    private Instant lastScrapedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "hero", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("equipped DESC, slot ASC, id ASC")
    private List<HeroItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "hero", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("endAt ASC, id ASC")
    private List<HeroAdventure> adventures = new ArrayList<>();

    protected Hero() {
    }

    public Hero(Account account) {
        this.account = account;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Keeps the updatedAt cursor fresh whenever any scraper field is touched. */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; touch(); }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; touch(); }

    public long getExperience() { return experience; }
    public void setExperience(long experience) { this.experience = experience; touch(); }

    public Long getExperienceForNext() { return experienceForNext; }
    public void setExperienceForNext(Long experienceForNext) { this.experienceForNext = experienceForNext; touch(); }

    public int getHealth() { return health; }
    public void setHealth(int health) { this.health = health; touch(); }

    public int getRegenPerHour() { return regenPerHour; }
    public void setRegenPerHour(int regenPerHour) { this.regenPerHour = regenPerHour; touch(); }

    public int getFreePoints() { return freePoints; }
    public void setFreePoints(int freePoints) { this.freePoints = freePoints; touch(); }

    public int getPointsFightingStrength() { return pointsFightingStrength; }
    public void setPointsFightingStrength(int p) { this.pointsFightingStrength = p; touch(); }

    public int getPointsOffBonus() { return pointsOffBonus; }
    public void setPointsOffBonus(int p) { this.pointsOffBonus = p; touch(); }

    public int getPointsDefBonus() { return pointsDefBonus; }
    public void setPointsDefBonus(int p) { this.pointsDefBonus = p; touch(); }

    public int getPointsResources() { return pointsResources; }
    public void setPointsResources(int p) { this.pointsResources = p; touch(); }

    public int getAdventurePoints() { return adventurePoints; }
    public void setAdventurePoints(int adventurePoints) { this.adventurePoints = adventurePoints; touch(); }

    public HeroStatus getStatus() { return status; }
    public void setStatus(HeroStatus status) { this.status = status; touch(); }

    public HeroProductionType getProductionType() { return productionType; }
    public void setProductionType(HeroProductionType productionType) { this.productionType = productionType; touch(); }

    public Long getHomeVillageDid() { return homeVillageDid; }
    public void setHomeVillageDid(Long homeVillageDid) { this.homeVillageDid = homeVillageDid; touch(); }

    public Long getCurrentVillageDid() { return currentVillageDid; }
    public void setCurrentVillageDid(Long currentVillageDid) { this.currentVillageDid = currentVillageDid; touch(); }

    public Instant getReviveAt() { return reviveAt; }
    public void setReviveAt(Instant reviveAt) { this.reviveAt = reviveAt; touch(); }

    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public void setLastScrapedAt(Instant lastScrapedAt) { this.lastScrapedAt = lastScrapedAt; touch(); }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<HeroItem> getItems() { return items; }
    public List<HeroAdventure> getAdventures() { return adventures; }

    public void replaceItems(List<HeroItem> fresh) {
        this.items.clear();
        for (HeroItem it : fresh) {
            it.bind(this);
            this.items.add(it);
        }
        touch();
    }

    public void replaceAdventures(List<HeroAdventure> fresh) {
        this.adventures.clear();
        for (HeroAdventure a : fresh) {
            a.bind(this);
            this.adventures.add(a);
        }
        touch();
    }
}
