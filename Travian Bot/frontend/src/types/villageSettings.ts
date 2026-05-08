/**
 * Per-village settings — NPC trader config + auto-scrape toggle + crop watcher.
 * NPC moved from AccountSettings to here so a capital feeding a wonder can
 * trade aggressively while a farm village stays hands-off on the same account.
 *
 * Mirrors backend VillageSettingsRequest / VillageSettingsResponse 1:1.
 */

export interface VillageSettings {
  villageId: number;

  npcEnabled: boolean;
  npcTriggerPct: number;
  npcRatioWood: number;
  npcRatioClay: number;
  npcRatioIron: number;
  npcRatioCrop: number;
  goldReserve: number;

  autoScrapeEnabled: boolean;
  /**
   * How often (in minutes) the background scheduler re-scrapes this village.
   * Previously a single global knob on {@code GeneralSettings}; moved per-village
   * so a capital under attack can poll every 2–5 min while a quiet support
   * village stays at 30–60. Range: 1..1440.
   */
  autoScrapeIntervalMinutes: number;
  /**
   * ISO timestamp of the last successful scrape for this village. Read-only —
   * the scraper owns it. Null until the first scrape lands.
   */
  lastScrapedAt: string | null;

  /**
   * Standalone "rescue before starvation" trigger that runs in parallel with
   * the regular NPC trader. When the granary dips below
   * {@link cropWatcherThresholdPct} the bot NPCs every other resource into crop —
   * independent of the regular ratio split.
   */
  cropWatcherEnabled: boolean;
  /** Granary fill % at or below which the watcher fires (0..100). */
  cropWatcherThresholdPct: number;
  /**
   * Minimum gold the bot must keep in reserve before the watcher will spend
   * any. Distinct from {@link goldReserve} so the watcher can be tuned more
   * conservatively.
   */
  cropWatcherMinGold: number;

  /**
   * Master toggle for the per-village troop training scheduler. When false
   * the bot never opens the barracks/stable/workshop on this village even
   * if {@code train-config} rows exist.
   */
  trainTroopsEnabled: boolean;
  /** Lower bound (minutes) of the random delay between two training cycles. */
  trainTroopsMinMinutes: number;
  /** Upper bound (minutes) of the random delay between two training cycles. */
  trainTroopsMaxMinutes: number;
}

/** PUT payload — full-object replace, same semantics as account settings. */
export type VillageSettingsUpdate = Omit<VillageSettings, 'villageId' | 'lastScrapedAt'>;
