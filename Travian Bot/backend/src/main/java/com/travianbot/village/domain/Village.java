package com.travianbot.village.domain;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A single Travian village on an {@link Account}'s map.
 * <p>
 * Villages are scoped to an account (same coordinates may exist on different servers / accounts),
 * and carry a small list of {@link BuildingQueueItem}s the bot will process in order.
 * Population and isCapital can be null/false until we start scraping state from the game.
 */
@Entity
@Table(
        name = "village",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_village_account_coords",
                columnNames = {"account_id", "coordinate_x", "coordinate_y"}
        )
)
public class Village {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "coordinate_x", nullable = false)
    private int coordinateX;

    @Column(name = "coordinate_y", nullable = false)
    private int coordinateY;

    @Column(nullable = false)
    private boolean capital;

    /** Nullable — only known once we've scraped state from the game. */
    @Column
    private Integer population;

    /**
     * The Travian in-game village id (did), discovered when scraping. Nullable until the
     * first scrape identifies this village. Used to build {@code ?newdid=<id>} URLs so the
     * scraper can switch to this village without a human click.
     */
    @Column(name = "travian_village_id")
    private Long travianVillageId;

    /**
     * Village group as defined in Travian's own "Village groups" feature (the Manage groups
     * dialog on the village-switcher). Mirrored read-only on our side — we never create or
     * rename groups, the scraper just reads whatever the user configured in-game. Nullable
     * because most villages sit in the "ungrouped" default.
     */
    @Column(name = "group_name", length = 100)
    private String groupName;

    /** Last successful scrape timestamp; null until first scrape. */
    @Column(name = "last_scraped_at")
    private Instant lastScrapedAt;

    // ---------- Resource stockpile (populated on dorf1 scrape) ----------
    // Parsed from the {@code var resources = { storage: {...}, maxStorage: {...} }}
    // JS blob on dorf1.php. All nullable until the first scrape populates them.
    // Currency is Travian-internal (no gold/silver) — just raw resource counts.

    @Column(name = "current_wood")
    private Long currentWood;

    @Column(name = "current_clay")
    private Long currentClay;

    @Column(name = "current_iron")
    private Long currentIron;

    @Column(name = "current_crop")
    private Long currentCrop;

    /** Warehouse capacity — same value for wood/clay/iron on a given village. */
    @Column(name = "warehouse_capacity")
    private Long warehouseCapacity;

    /** Granary capacity — separate building from warehouse, only holds crop. */
    @Column(name = "granary_capacity")
    private Long granaryCapacity;

    /**
     * Net crop production per hour as reported by dorf1's
     * {@code production.l4} JS blob — positive when the village has surplus,
     * negative when the troop / settler upkeep exceeds field output (the
     * "starving" condition the Crop watcher gates on). Nullable: older Travian
     * pages may not emit the production block, and {@code 0} is a meaningful
     * value (a freshly-built no-troop village can legitimately tick at 0/h).
     */
    @Column(name = "current_crop_production")
    private Long currentCropProduction;

    /** When the stockpile block was last refreshed. Distinct from {@link #lastScrapedAt}
     *  which tracks the dorf1/dorf2 full scrape — some code paths refresh just the
     *  stockpile (e.g. after an NPC trade). Null until first scrape. */
    @Column(name = "resources_updated_at")
    private Instant resourcesUpdatedAt;

    /**
     * Position in the user-controlled village list shown in the Villages page
     * sidebar. Zero-based. Rows created before this column existed default to
     * 0 — the first read after upgrade tiebreaks on {@code createdAt} so the
     * apparent ordering doesn't change. Once the user reorders, the index is
     * set explicitly via {@code VillageService.reorder}.
     * <p>
     * This has nothing to do with Travian's ingame village-switcher order —
     * that's controlled by the game's own village-groups feature and is not
     * editable via the client API.
     */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    /**
     * Manual Tournament Square level (0..20) the user typed in for this
     * village. {@code null} means "unknown" — the analyzer falls back to
     * brute-forcing TS levels as before. When set, the inverse solver
     * (and future return-catch math) can treat this as a fixed pin,
     * shrinking the candidate space.
     * <p>
     * Stored on Village so it survives a Clear-all of the troop snapshots
     * (the user shouldn't lose a hand-classified TS level just because they
     * re-imported troop counts).
     */
    @Column(name = "ts_level")
    private Integer tsLevel;

    /**
     * User-applied role tag (off hammer, deff, pve, …). Free for the
     * frontend to read for ordering / filtering — the bot itself doesn't
     * yet branch on it, but the field is plumbed end-to-end so the next
     * planner pass can prefer e.g. OFF_HAMMER villages as snipe launchers.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "village_type", length = 16)
    private VillageType villageType;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "village", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC, id ASC")
    private List<BuildingQueueItem> queue = new ArrayList<>();

    protected Village() {
    }

    public Village(Account account, String name, int coordinateX, int coordinateY) {
        this.account = account;
        this.name = name;
        this.coordinateX = coordinateX;
        this.coordinateY = coordinateY;
        this.capital = false;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getName() { return name; }
    public int getCoordinateX() { return coordinateX; }
    public int getCoordinateY() { return coordinateY; }
    public boolean isCapital() { return capital; }
    public Integer getPopulation() { return population; }
    public Long getTravianVillageId() { return travianVillageId; }
    public String getGroupName() { return groupName; }
    public Instant getLastScrapedAt() { return lastScrapedAt; }
    public Long getCurrentWood() { return currentWood; }
    public Long getCurrentClay() { return currentClay; }
    public Long getCurrentIron() { return currentIron; }
    public Long getCurrentCrop() { return currentCrop; }
    public Long getWarehouseCapacity() { return warehouseCapacity; }
    public Long getGranaryCapacity() { return granaryCapacity; }
    public Long getCurrentCropProduction() { return currentCropProduction; }
    public Instant getResourcesUpdatedAt() { return resourcesUpdatedAt; }
    public int getOrderIndex() { return orderIndex; }
    public Integer getTsLevel() { return tsLevel; }
    public VillageType getVillageType() { return villageType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<BuildingQueueItem> getQueue() { return queue; }

    /**
     * Set the manual Tournament Square level. Caller is responsible for
     * range-validating (0..20) — we treat anything else the same as null
     * to keep the entity tolerant of bad input.
     */
    public void setTsLevel(Integer tsLevel) {
        if (tsLevel != null && (tsLevel < 0 || tsLevel > 20)) {
            this.tsLevel = null;
        } else {
            this.tsLevel = tsLevel;
        }
        touch();
    }

    public void setVillageType(VillageType villageType) {
        this.villageType = villageType;
        touch();
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
        touch();
    }

    public void rename(String name) {
        this.name = name;
        touch();
    }

    public void moveTo(int x, int y) {
        this.coordinateX = x;
        this.coordinateY = y;
        touch();
    }

    public void setCapital(boolean capital) {
        this.capital = capital;
        touch();
    }

    public void setPopulation(Integer population) {
        this.population = population;
        touch();
    }

    public void setTravianVillageId(Long travianVillageId) {
        this.travianVillageId = travianVillageId;
        touch();
    }

    public void setGroupName(String groupName) {
        // Normalize empty/whitespace to null so "ungrouped" is one canonical state.
        this.groupName = (groupName == null || groupName.isBlank()) ? null : groupName.trim();
        touch();
    }

    public void markScraped() {
        this.lastScrapedAt = Instant.now();
        touch();
    }

    /**
     * Atomically refresh the stockpile block from a dorf1 scrape. All six counts
     * are set together so downstream consumers never see a half-updated view
     * (e.g. fresh crop vs stale wood) which would confuse the NPC trigger check.
     * Touch timestamps are updated here and in {@link #touch()} — both the
     * resource-specific and the general {@code updatedAt}.
     */
    public void setStockpile(long wood, long clay, long iron, long crop,
                             long warehouseCapacity, long granaryCapacity,
                             Long cropProduction) {
        this.currentWood = wood;
        this.currentClay = clay;
        this.currentIron = iron;
        this.currentCrop = crop;
        this.warehouseCapacity = warehouseCapacity;
        this.granaryCapacity = granaryCapacity;
        // Null is "production block missing from the page" — leave whatever
        // we had so a single bad scrape doesn't blank a previously-known
        // value. Callers that want to clear it should set to a non-null 0
        // explicitly.
        if (cropProduction != null) {
            this.currentCropProduction = cropProduction;
        }
        this.resourcesUpdatedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
