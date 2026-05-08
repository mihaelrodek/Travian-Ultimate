/**
 * Training buildings the per-village training config targets. The backend
 * has the same enum; keeping it string-literal on the frontend keeps TS
 * exhaustiveness checks honest.
 */
export const TROOP_BUILDINGS = ['BARRACKS', 'STABLE', 'WORKSHOP'] as const;
export type TroopBuilding = (typeof TROOP_BUILDINGS)[number];

/**
 * One entry in the tribe-scoped troop catalogue. Fetched per tribe from
 * {@code GET /api/troops/catalog?tribe=X} — the backend derives the tribe
 * from the account's settings.
 */
export interface TroopCatalogEntry {
  code: string;
  displayName: string;
  building: TroopBuilding;
  /** 1..8 — position within Travian's t1..t10 troop slots (9/10 omitted). */
  troopSlot: number;
}

/**
 * Per-village training intent: a min/max quantity range for one troop. The
 * scheduler picks a uniform random value in [{@code minQty}, {@code maxQty}]
 * each cycle. A row with both at 0 means "don't train this" — the backend
 * doesn't persist those.
 */
export interface TrainConfigItem {
  troopCode: string;
  minQty: number;
  maxQty: number;
}

export interface TrainConfigResponse {
  villageId: number;
  items: TrainConfigItem[];
}

export interface TrainConfigRequest {
  items: TrainConfigItem[];
}
