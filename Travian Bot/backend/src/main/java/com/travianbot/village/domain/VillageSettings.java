package com.travianbot.village.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Per-village configuration. One row per {@link Village}. Previously these fields
 * (NPC trader ratios, gold reserve, auto-scrape toggle) lived on
 * {@code AccountSettings} and applied account-wide, which didn't fit real play —
 * a capital may need aggressive NPC to feed a wonder while a support village
 * should be hands-off.
 * <p>
 * Kept as a dedicated entity rather than extra columns on {@link Village} to keep
 * the read path for the villages list cheap (we don't need settings when
 * rendering the sidebar). The {@code village_id} FK is {@code unique} so a village
 * can only ever have one settings row, and the cascade-delete is handled by
 * {@link com.travianbot.village.service.VillageService#delete} explicitly (we
 * don't want JPA silently deleting settings if the Village entity gets detached).
 */
@Entity
@Table(name = "village_settings")
public class VillageSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "village_id", nullable = false, unique = true)
    private Village village;

    // ---------- NPC / resource balancing ----------
    @Column(name = "npc_enabled", nullable = false)
    private boolean npcEnabled = false;

    @Column(name = "npc_trigger_pct", nullable = false)
    private int npcTriggerPct = 95;

    @Column(name = "npc_ratio_wood", nullable = false)
    private int npcRatioWood = 25;

    @Column(name = "npc_ratio_clay", nullable = false)
    private int npcRatioClay = 25;

    @Column(name = "npc_ratio_iron", nullable = false)
    private int npcRatioIron = 25;

    @Column(name = "npc_ratio_crop", nullable = false)
    private int npcRatioCrop = 25;

    @Column(name = "gold_reserve", nullable = false)
    private int goldReserve = 10;

    // ---------- Auto-scrape ----------
    /**
     * When {@code true} the village-scrape scheduler may re-scrape this village
     * on its regular interval. When {@code false} it's skipped — useful for a
     * capital the user is actively scraping from the UI so the two don't fight.
     */
    @Column(name = "auto_scrape_enabled", nullable = false)
    private boolean autoScrapeEnabled = true;

    /**
     * How often the background scheduler re-scrapes this village, in minutes.
     * Previously a single global {@code villageScrapeIntervalMinutes} on
     * {@code GeneralSettings} drove this for every village, which didn't fit real
     * play — a capital under active attack needs aggressive polling (2–5 min) while
     * a quiet support village is fine at 30–60. Range is enforced by the DTO
     * (1..1440); the column itself keeps a safe default of 15 so rows predating
     * this field don't start firing every minute on upgrade.
     */
    @Column(name = "auto_scrape_interval_minutes", nullable = false)
    private int autoScrapeIntervalMinutes = 15;

    // ---------- Crop watcher ----------
    /**
     * Standalone "rescue the village before it starves" trigger that runs in
     * parallel with the standard {@link #npcEnabled} trader. When the granary
     * dips below {@link #cropWatcherThresholdPct}% the bot NPCs every other
     * resource into crop — independent of the regular ratio split, because the
     * village is in trouble and balanced trading isn't going to fix it.
     */
    @Column(name = "crop_watcher_enabled", nullable = false)
    private boolean cropWatcherEnabled = false;

    /**
     * Percentage of granary capacity at which the watcher fires (0..100). The
     * default of 20 is the rough "still has time to react before troops start
     * dying" mark on a normal-speed server; tweak per village.
     */
    @Column(name = "crop_watcher_threshold_pct", nullable = false)
    private int cropWatcherThresholdPct = 20;

    /**
     * Minimum gold the bot must keep in reserve before the crop watcher will
     * spend any to NPC. Distinct from {@link #goldReserve} — this lets the
     * watcher run more conservatively than the standard NPC if the user wants
     * to hoard gold for instant-finish or a Plus extension.
     */
    @Column(name = "crop_watcher_min_gold", nullable = false)
    private int cropWatcherMinGold = 0;

    // ---------- Troop training (per-village) ----------
    /**
     * Master toggle for the troop-training scheduler. When false the bot
     * never opens the barracks/stable/workshop on this village even if
     * {@link com.travianbot.village.domain.VillageTrainConfig} rows exist.
     * Modeled per-village (not per-account) so a capital can train heavy
     * cavalry on its own cadence while a farm village stays quiet.
     */
    @Column(name = "train_troops_enabled", nullable = false)
    private boolean trainTroopsEnabled = false;

    /**
     * Minimum minutes between two consecutive training cycles on this
     * village. The scheduler picks a uniform random delay in
     * [{@code trainTroopsMinMinutes}, {@code trainTroopsMaxMinutes}] after
     * each successful cycle — same humanization shape we use for click
     * delays, just at a different time scale.
     */
    @Column(name = "train_troops_min_minutes", nullable = false)
    private int trainTroopsMinMinutes = 15;

    @Column(name = "train_troops_max_minutes", nullable = false)
    private int trainTroopsMaxMinutes = 60;

    protected VillageSettings() {
    }

    public VillageSettings(Village village) {
        this.village = village;
    }

    // ---------- Accessors ----------
    public Long getId() { return id; }
    public Village getVillage() { return village; }

    public boolean isNpcEnabled() { return npcEnabled; }
    public void setNpcEnabled(boolean npcEnabled) { this.npcEnabled = npcEnabled; }
    public int getNpcTriggerPct() { return npcTriggerPct; }
    public void setNpcTriggerPct(int npcTriggerPct) { this.npcTriggerPct = npcTriggerPct; }
    public int getNpcRatioWood() { return npcRatioWood; }
    public void setNpcRatioWood(int npcRatioWood) { this.npcRatioWood = npcRatioWood; }
    public int getNpcRatioClay() { return npcRatioClay; }
    public void setNpcRatioClay(int npcRatioClay) { this.npcRatioClay = npcRatioClay; }
    public int getNpcRatioIron() { return npcRatioIron; }
    public void setNpcRatioIron(int npcRatioIron) { this.npcRatioIron = npcRatioIron; }
    public int getNpcRatioCrop() { return npcRatioCrop; }
    public void setNpcRatioCrop(int npcRatioCrop) { this.npcRatioCrop = npcRatioCrop; }
    public int getGoldReserve() { return goldReserve; }
    public void setGoldReserve(int goldReserve) { this.goldReserve = goldReserve; }

    public boolean isAutoScrapeEnabled() { return autoScrapeEnabled; }
    public void setAutoScrapeEnabled(boolean autoScrapeEnabled) { this.autoScrapeEnabled = autoScrapeEnabled; }

    public int getAutoScrapeIntervalMinutes() { return autoScrapeIntervalMinutes; }
    public void setAutoScrapeIntervalMinutes(int autoScrapeIntervalMinutes) { this.autoScrapeIntervalMinutes = autoScrapeIntervalMinutes; }

    public boolean isCropWatcherEnabled() { return cropWatcherEnabled; }
    public void setCropWatcherEnabled(boolean cropWatcherEnabled) { this.cropWatcherEnabled = cropWatcherEnabled; }
    public int getCropWatcherThresholdPct() { return cropWatcherThresholdPct; }
    public void setCropWatcherThresholdPct(int cropWatcherThresholdPct) { this.cropWatcherThresholdPct = cropWatcherThresholdPct; }
    public int getCropWatcherMinGold() { return cropWatcherMinGold; }
    public void setCropWatcherMinGold(int cropWatcherMinGold) { this.cropWatcherMinGold = cropWatcherMinGold; }

    public boolean isTrainTroopsEnabled() { return trainTroopsEnabled; }
    public void setTrainTroopsEnabled(boolean trainTroopsEnabled) { this.trainTroopsEnabled = trainTroopsEnabled; }
    public int getTrainTroopsMinMinutes() { return trainTroopsMinMinutes; }
    public void setTrainTroopsMinMinutes(int trainTroopsMinMinutes) { this.trainTroopsMinMinutes = trainTroopsMinMinutes; }
    public int getTrainTroopsMaxMinutes() { return trainTroopsMaxMinutes; }
    public void setTrainTroopsMaxMinutes(int trainTroopsMaxMinutes) { this.trainTroopsMaxMinutes = trainTroopsMaxMinutes; }
}
