/**
 * Types for the intel / inactive-search module.
 *
 * The intel feature lets the bot operator find good farming targets near
 * their own villages by parsing the daily public {@code map.sql} dump that
 * every Travian server publishes. A player is "inactive" when their
 * population hasn't grown over the last week — see backend
 * {@code InactiveSearchService} for the exact heuristic.
 */

/** Canonical tribe ids in T4.6 Legends. Nature (4) is never present in results. */
export const TRIBE_IDS = {
  ROMANS: 1,
  TEUTONS: 2,
  GAULS: 3,
  NATARS: 5,
  EGYPTIANS: 6,
  HUNS: 7,
} as const;

export const TRIBE_LABELS: Record<number, string> = {
  1: 'Romans',
  2: 'Teutons',
  3: 'Gauls',
  5: 'Natars',
  6: 'Egyptians',
  7: 'Huns',
};

/**
 * One village on the interactive world map (MAP1). Mirrors the backend
 * `WorldMapVillageResponse` — village geometry flattened with the owning
 * player's attributes so the canvas can colour/label each dot without a join.
 * Player fields are null for the rare orphan village whose player row is missing.
 */
export interface WorldMapVillage {
  x: number;
  y: number;
  population: number;
  name: string;
  capital: boolean;
  playerName: string | null;
  allianceName: string | null;
  tribeId: number | null;
  inactive: boolean;
  cropperClassification: string | null;
}

/** Full world-map payload — server extent + every settled village. */
export interface WorldMap {
  /** Map half-width in fields (coords span -mapRadius..mapRadius). Null if unknown. */
  mapRadius: number | null;
  lastSyncedAt: string | null;
  villageCount: number;
  villages: WorldMapVillage[];
}

/** Row in the search-results table. */
export interface InactivePlayer {
  id: number;
  travianUid: number;
  name: string;
  allianceName: string | null;
  tribeId: number;
  currentPopulation: number;
  /** {@code currentPopulation - populationSnapshot7dAgo}, or null if no snapshot yet. */
  populationDelta7d: number | null;
  villageCount: number;
  daysSincePopChange: number | null;
  /** Euclidean distance in map fields to the pivot village, when filter set. */
  nearestVillageDistance: number | null;
  isInactive: boolean;
}

/** Full player view + their tracked villages. */
export interface InactivePlayerDetail {
  id: number;
  travianUid: number;
  name: string;
  allianceName: string | null;
  allianceId: number | null;
  tribeId: number;
  currentPopulation: number;
  populationSnapshot7dAgo: number | null;
  populationSnapshot30dAgo: number | null;
  villageCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
  lastPopChangeAt: string | null;
  isInactive: boolean;
  villages: InactiveVillage[];
}

export interface InactiveVillage {
  /** Local MapVillage row id — used by the chart endpoint. */
  id: number;
  travianVid: number;
  name: string;
  x: number;
  y: number;
  population: number;
  isCapital: boolean;
  lastSeenAt: string;
}

/** Sortable columns for inactive search. */
export type InactiveSortBy =
  | 'distance'
  | 'population'
  | 'idleDays'
  | 'villageCount'
  | 'lastSeen';

export type SortDir = 'asc' | 'desc';

/** Backend-side filters mirrored as URL query params. All optional. */
export interface InactiveSearchFilters {
  minIdleDays?: number;
  maxPopulation?: number;
  minPopulation?: number;
  /** Exclude players whose 7-day pop growth exceeds this. */
  maxPopulationIncrease?: number;
  minVillageCount?: number;
  maxVillageCount?: number;
  /** Coordinate bounding-box on the player's villages. */
  xMin?: number;
  xMax?: number;
  yMin?: number;
  yMax?: number;
  nearVillageId?: number;
  maxDistance?: number;
  /** Comma-separated tribe ids — the API parses CSV. */
  tribeIds?: number[];
  allianceExclude?: string;
  allianceInclude?: string;
  /**
   * Filter to players whose alliance's Travian aid matches this. Set from
   * the Alliance autocomplete on the page (or from {@code ?alliance=N} in the
   * URL when navigated to from the Alliances page).
   */
  allianceTravianId?: number;
  /** Player was seen in map.sql within the last N days. */
  lastSeenWithinDays?: number;
  inactiveOnly?: boolean;
  sortBy?: InactiveSortBy;
  sortDir?: SortDir;
  page?: number;
  size?: number;
}

/** Page envelope (mirrors {@code InactivePlayerPageResponse}). */
export interface InactivePlayerPage {
  items: InactivePlayer[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface IntelSyncResponse {
  playersSynced: number;
}

/** Single point on the per-village pop-history sparkline. */
export interface VillagePopulationPoint {
  capturedAt: string;
  population: number;
}

/**
 * Cropper-classification buckets matching the backend's
 * {@code CropperClassifierService} constants. {@code UNKNOWN} is present for
 * completeness but isn't a useful filter — the API drops it automatically.
 */
export const CROPPER_CLASSIFICATIONS = [
  'HIGH_15C',
  'LIKELY_15C',
  'POSSIBLE_15C',
  'LIKELY_9C',
  'UNKNOWN',
] as const;

export type CropperClassification = (typeof CROPPER_CLASSIFICATIONS)[number];

/** Human-readable labels used in the results table + filter checkboxes. */
export const CROPPER_CLASSIFICATION_LABELS: Record<CropperClassification, string> = {
  HIGH_15C: 'High 15c',
  LIKELY_15C: 'Likely 15c',
  POSSIBLE_15C: 'Possible 15c',
  LIKELY_9C: 'Likely 9c',
  UNKNOWN: 'Unknown',
};

/**
 * One row in the cropper-finder results table. Mirrors the backend
 * {@code CropperCandidateResponse} record field-for-field.
 */
export interface CropperCandidate {
  mapVillageId: number;
  playerUid: number;
  playerName: string | null;
  allianceName: string | null;
  tribeId: number | null;
  x: number;
  y: number;
  population: number;
  isCapital: boolean;
  cropperClassification: CropperClassification;
  distanceFields: number;
  playerVillageCount: number | null;
  playerLooksActive: boolean;
}

/** Query parameters for {@code intelApi.findCroppers}. */
export interface CropperSearchParams {
  nearVillageId: number;
  maxDistance?: number;
  classifications?: CropperClassification[];
  includeOccupied?: boolean;
}

export interface CropperReclassifyResponse {
  classified: number;
}

// ---------------------------------------------------------------------------
// Alliances module
// ---------------------------------------------------------------------------

/**
 * Row in the alliance list — one per alliance present in this server's most
 * recent {@code map.sql} aggregation. Mirrors backend {@code AllianceResponse}.
 *
 * {@code lastDetailScrapedAt} is null until the user clicks "Refresh details"
 * on the modal at least once — the daily {@code map.sql} sync only fills the
 * roster aggregates, not the description / bonuses (those come from the
 * on-demand {@code AllianceDetailScraper}).
 */
export interface Alliance {
  id: number;
  serverId: number;
  /** Travian's own aid — used in {@code /allianz.php?aid=N} links. */
  travianAid: number;
  name: string;
  memberCount: number;
  totalPopulation: number;
  averagePopulation: number;
  firstSeenAt: string;
  lastSeenAt: string;
  lastDetailScrapedAt: string | null;
}

export interface AllianceMember {
  /** Travian uid — clickable to open the player detail modal. */
  uid: number;
  name: string;
  population: number;
  /** Role string as scraped — "Leader", "Side leader", "Member", or null. */
  role: string | null;
}

/** Full alliance view — adds scraped description + bonuses + roster. */
export interface AllianceDetail extends Alliance {
  description: string | null;
  /** Level 0..20 for each known bonus, or null if not present / not scraped. */
  bonusDiplomacy: number | null;
  bonusMining: number | null;
  bonusScout: number | null;
  bonusBrewery: number | null;
  /**
   * Raw JSON map of {@code { bonusName: level }} for any bonus the backend
   * didn't model as a first-class field — rendered as a small key/value list
   * below the main bonus grid. Parsed defensively on the client (try/catch);
   * malformed payloads are silently skipped.
   */
  rawBonusesJson: string | null;
  members: AllianceMember[];
}

/** Page envelope (mirrors {@code AlliancePageResponse}). */
export interface AlliancePage {
  items: Alliance[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Sortable columns for the alliance list. */
export type AllianceSortBy =
  | 'name'
  | 'memberCount'
  | 'totalPopulation'
  | 'averagePopulation'
  | 'lastSeenAt';

/** Backend-side filters mirrored as URL query params. All optional. */
export interface AllianceSearchFilters {
  /** Substring match on alliance name; case-insensitive. */
  search?: string;
  minMembers?: number;
  maxMembers?: number;
  sortBy?: AllianceSortBy;
  sortDir?: SortDir;
  page?: number;
  size?: number;
}
