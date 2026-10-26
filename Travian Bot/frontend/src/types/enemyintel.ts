/**
 * TS counterparts to the backend's enemy-intel DTOs. The shapes mirror
 * {@code EnemyPlayerResponse} / {@code EnemyPlayerDetailResponse} /
 * {@code EnemyProfileSnapshotResponse} one-for-one — keep these in sync
 * when adding fields server-side.
 */

export interface EnemyPlayer {
  id: number;
  accountId: number;
  travianUid: number;
  playerName: string;
  allianceTravianId: number | null;
  allianceName: string | null;
  /** 1..9 Travian internal tribe id, or null if not detected yet. */
  tribeId: number | null;
  currentPopulation: number | null;
  villageCount: number | null;
  /** Null = hero not visible on profile (locked, sitter-hidden, parse miss). */
  heroLevel: number | null;
  heroXp: number | null;
  /** Percent bonus from the hero panel. */
  heroProductionBonus: number | null;
  /** Id of the most recent IncomingAttack that flagged this player. */
  lastInteractionAttackId: number | null;
  /** ISO-8601 instant — when we first added them. */
  firstSeenAt: string;
  /** ISO-8601 instant — last successful profile scrape. */
  lastScrapedAt: string;
  /** ISO-8601 instant — when any tracked metric last differed. Null until first change. */
  lastChangeAt: string | null;
  /** Backend-derived "days since lastScrapedAt" — used for the staleness chip. */
  daysSinceLastScrape: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface EnemyProfileSnapshot {
  id: number;
  enemyPlayerId: number;
  capturedAt: string;
  heroLevel: number | null;
  heroXp: number | null;
  heroProductionBonus: number | null;
  currentPopulation: number | null;
  villageCount: number | null;
  allianceTravianId: number | null;
  allianceName: string | null;
  /** Human-readable diff vs the prior snapshot. Empty string means "no meaningful change". */
  diffSummary: string | null;
}

export interface EnemyPlayerDetail {
  player: EnemyPlayer;
  snapshots: EnemyProfileSnapshot[];
}
