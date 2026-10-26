export type FarmlistType = 'PVE' | 'PVP';

export interface FarmlistTarget {
  id: number | null;
  /** Rally-point list-item id, used by the scheduler to issue direct sends. Null before the first scrape sets it. */
  travianTargetId: number | null;
  name: string | null;
  coordinateX: number;
  coordinateY: number;
  /** Raw JSON string, e.g. {"t1": 50, "t4": 2}. The editor parses/validates. */
  troopsJson: string;
  enabled: boolean;
  lastSentAt: string | null;
}

export interface Farmlist {
  id: number;
  accountId: number;
  /** The Travian-side list id (lid on the send form). Null until first scrape. */
  travianListId: number | null;
  name: string;
  description: string | null;
  enabled: boolean;
  type: FarmlistType;
  /** Null = never auto-send (manual only). */
  sendIntervalMinutes: number | null;
  /** Free-text tag; lists sharing a tag are sent together and share a baseline. */
  groupTag: string | null;
  lastSentAt: string | null;
  createdAt: string;
  updatedAt: string;
  targets: FarmlistTarget[];
}

export interface FarmlistCreate {
  accountId: number;
  name: string;
  description: string | null;
  enabled: boolean;
  type: FarmlistType;
  sendIntervalMinutes: number | null;
  groupTag: string | null;
  targets: FarmlistTarget[];
}

export interface FarmlistUpdate {
  name: string;
  description: string | null;
  enabled: boolean;
  type: FarmlistType;
  sendIntervalMinutes: number | null;
  groupTag: string | null;
  targets: FarmlistTarget[];
}

export interface SendListResponse {
  farmlistId: number;
  targetsSent: number;
}

export interface ScrapeFarmlistsResponse {
  farmlistCount: number;
}

export interface SendAllFarmlistsResponse {
  listsSent: number;
}

/**
 * Per-target running metrics rolled up by the backend analyzer.
 * Targets that have never been raided come back with zero defaults so the
 * UI always sees a row per target — see {@code FarmlistTargetMetricResponse}.
 */
export interface FarmlistTargetMetric {
  farmlistTargetId: number;
  targetX: number;
  targetY: number;
  targetName: string | null;
  totalRaids: number;
  totalResourcesLooted: number;
  totalOurTroopsLost: number;
  consecutiveLossRaids: number;
  /** GREEN — farming healthily, YELLOW — thin/losses building, RED — prune candidate. */
  trafficLight: 'GREEN' | 'YELLOW' | 'RED';
  prunCandidate: boolean;
  lastRaidAt: string | null;
  lastFullHaulAt: string | null;
}

/** Mirror of the backend RaidReport row — what came back from a single raid. */
export interface RaidReport {
  id: number;
  accountId: number;
  farmlistId: number;
  farmlistTargetId: number | null;
  travianReportId: number;
  raidLaunchedAt: string | null;
  raidReturnedAt: string;
  targetX: number;
  targetY: number;
  targetVillageName: string | null;
  woodLooted: number;
  clayLooted: number;
  ironLooted: number;
  cropLooted: number;
  carryCapacityTotal: number | null;
  wasFullHaul: boolean;
  ourTroopsLost: number;
  enemyTroopsLost: number;
  outcome: 'VICTORY_NO_LOSS' | 'VICTORY_WITH_LOSS' | 'DEFEAT' | 'UNKNOWN';
}

export interface ScrapeRaidReportsResponse {
  scraped: number;
}
