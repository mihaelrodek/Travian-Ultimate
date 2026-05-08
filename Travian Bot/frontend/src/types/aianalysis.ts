/**
 * Oasis tile categories returned by the AI Analysis scan. Mirrors the
 * backend {@code OasisType} enum 1:1.
 */
export const OASIS_TYPES = ['WOOD', 'CLAY', 'IRON', 'CROP', 'MIXED', 'UNKNOWN'] as const;
export type OasisType = (typeof OASIS_TYPES)[number];

/**
 * One tile in a scan. {@code occupiedByPlayer} is the in-game owner if the
 * oasis has been conquered, null when it's still up for grabs.
 */
export interface OasisTile {
  x: number;
  y: number;
  type: OasisType;
  bonusPct: number;
  distanceTiles: number;
  occupiedByPlayer: string | null;
}

/**
 * One AI Analysis scan run. Holds the center coordinate + radius so the
 * dashboard can re-scan with the same parameters and label the result, plus
 * cached counts per resource type for the summary cards.
 */
export interface OasisScan {
  id: number;
  accountId: number;
  centerX: number;
  centerY: number;
  radius: number;
  startedAt: string;
  completedAt: string | null;
  woodCount: number;
  clayCount: number;
  ironCount: number;
  cropCount: number;
  mixedCount: number;
  tiles: OasisTile[];
}

export interface StartScanRequest {
  centerX: number;
  centerY: number;
  radius: number;
}
