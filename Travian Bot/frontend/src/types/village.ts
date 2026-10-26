export const QUEUE_STATUSES = ['PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED'] as const;
export type QueueStatus = (typeof QUEUE_STATUSES)[number];

/**
 * One item in a village's building queue. {@code id} is absent for rows the user
 * just added in the editor — the backend creates them on save.
 */
export interface BuildingQueueItem {
  id: number | null;
  buildingType: string;
  slot: number | null;
  targetLevel: number;
  orderIndex: number;
  status: QueueStatus;
  createdAt: string | null;
  completedAt: string | null;
  /**
   * Expected in-game finish time for RUNNING upgrades, ISO-8601 UTC. Populated
   * by the scraper when it can match this queue row to Travian's
   * {@code #buildingList} sidebar countdown. Null for PENDING rows and for
   * RUNNING rows we haven't yet matched to a sidebar entry.
   */
  finishesAt: string | null;
  resultMessage: string | null;
}

export const RESOURCE_TYPES = ['WOOD', 'CLAY', 'IRON', 'CROP'] as const;
export type ResourceType = (typeof RESOURCE_TYPES)[number];

/**
 * One of the 18 resource tiles outside the village (dorf1.php slots 1..18).
 * Populated by the scraper.
 */
export interface ResourceField {
  id: number;
  slotId: number;
  gid: number;
  type: ResourceType;
  level: number;
  underConstruction: boolean;
  updatedAt: string;
}

/**
 * One of up to 22 building slots inside the village (dorf2.php slots 19..40).
 * Empty slots have gid = 0 and name null.
 */
export interface Building {
  id: number;
  slotId: number;
  gid: number;
  name: string | null;
  level: number;
  underConstruction: boolean;
  updatedAt: string;
}

/**
 * Community-standard role tags for villages. The bot doesn't infer these —
 * the user tags each village in the Village troops page. Mirrors the
 * backend `VillageType` enum 1:1.
 */
export const VILLAGE_TYPES = [
  'OFF_HAMMER',
  'OFF_GHOST',
  'DEFF',
  'WWK',
  'WWR',
  'PVE',
] as const;
export type VillageType = (typeof VILLAGE_TYPES)[number];

/** Human-readable labels for the village-type picker. */
export const VILLAGE_TYPE_LABELS: Record<VillageType, string> = {
  OFF_HAMMER: 'Off hammer',
  OFF_GHOST: 'Off ghost',
  DEFF: 'Deff',
  WWK: 'WWK',
  WWR: 'WWR',
  PVE: 'PVE',
};

export interface Village {
  id: number;
  accountId: number;
  name: string;
  coordinateX: number;
  coordinateY: number;
  capital: boolean;
  population: number | null;
  travianVillageId: number | null;
  /**
   * Name of the in-game village group (Travian's "Village groups" feature) or null when
   * ungrouped. Read-only — the bot never creates or renames groups, it mirrors what the
   * player configured in-game.
   */
  groupName: string | null;
  /**
   * User-chosen position in the Villages sidebar. Zero-based. Ties are broken
   * by createdAt on the backend — see VillageRepository.
   */
  orderIndex: number;
  /**
   * Manual Tournament Square level (0..20) typed by the user. Null when
   * unset — the inverse solver falls back to brute-forcing TS levels.
   */
  tsLevel: number | null;
  /**
   * User-applied role tag — null when unclassified.
   */
  villageType: VillageType | null;
  lastScrapedAt: string | null;
  createdAt: string;
  updatedAt: string;
  queue: BuildingQueueItem[];
  resourceFields: ResourceField[];
  buildings: Building[];
}

export interface VillageCreate {
  accountId: number;
  name: string;
  coordinateX: number;
  coordinateY: number;
  capital: boolean;
  population: number | null;
}

export interface VillageUpdate {
  name: string;
  coordinateX: number;
  coordinateY: number;
  capital: boolean;
  population: number | null;
}

/**
 * Entry in the static building catalog returned by GET /api/buildings/catalog.
 */
export interface BuildingCatalogEntry {
  gid: number;
  name: string;
}

/**
 * Canonical list of T4.6 building types. Kept in the frontend so the editor has
 * a dropdown; the backend stores as free-form string so new entries don't
 * require a migration.
 */
export const BUILDING_TYPES = [
  // Resource fields
  'WOODCUTTER',
  'CLAY_PIT',
  'IRON_MINE',
  'CROPLAND',
  // Infrastructure
  'MAIN_BUILDING',
  'WAREHOUSE',
  'GRANARY',
  'MARKETPLACE',
  'EMBASSY',
  'RESIDENCE',
  'PALACE',
  'TREASURY',
  'TOURNAMENT_SQUARE',
  'RALLY_POINT',
  'CITY_WALL',
  // Military
  'BARRACKS',
  'STABLE',
  'WORKSHOP',
  'ACADEMY',
  'SMITHY',
  'HEROS_MANSION',
  // Economy
  'SAWMILL',
  'BRICKYARD',
  'IRON_FOUNDRY',
  'GRAIN_MILL',
  'BAKERY',
  'CRANNY',
  'TRAPPER',
  // Rare
  'WONDER_OF_THE_WORLD',
  'GREAT_WAREHOUSE',
  'GREAT_GRANARY',
  'GREAT_BARRACKS',
  'GREAT_STABLE',
] as const;

export type BuildingType = (typeof BUILDING_TYPES)[number];
