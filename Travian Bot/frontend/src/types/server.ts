/**
 * Types for the server-scoped intel / cropper modules.
 *
 * A "server" here is a Travian world (one URL like `https://ts7.x1.europe.travian.com/`).
 * Local accounts each belong to exactly one server; the intel + cropper features
 * scope their queries to the server itself so multiple accounts on the same
 * world share a single map dataset.
 *
 * Lifecycle mirrors the backend {@code TravianServer} entity — `id` is assigned
 * by the API on create, `lastMapSyncedAt` is null until the daily sync has run
 * at least once, and `accountCount` is a denormalized convenience field so the
 * picker can show "X accounts use this server" without a second round trip.
 */
export interface Server {
  id: number;
  url: string;
  name: string;
  region: string | null;
  gameWorld: string | null;
  active: boolean;
  /** ISO-8601 UTC of the last successful map.sql sync, null if never synced. */
  lastMapSyncedAt: string | null;
  /** Player count from the most recent map.sql sync, null if never synced. */
  lastMapSyncPlayerCount: number | null;
  /** How many local accounts currently target this server URL. */
  accountCount: number;
  createdAt: string;
}

/** Body for POST /api/servers. */
export interface CreateServerRequest {
  url: string;
  name: string;
  region?: string;
  gameWorld?: string;
}

/** Body for PUT /api/servers/{id}. All fields optional — partial update. */
export interface UpdateServerRequest {
  name?: string;
  region?: string;
  gameWorld?: string;
  active?: boolean;
}

/**
 * One village owned by an account on this server. Used as the "near my village"
 * pivot source on the intel / cropper pages — the picker can offer every
 * village across every account that plays this world, not just the currently
 * scoped account.
 */
export interface PivotVillage {
  id: number;
  accountId: number;
  accountName: string;
  villageName: string;
  x: number;
  y: number;
}

/**
 * Summary of a one-shot discovery probe — mirrors the backend
 * {@code DiscoveryReportResponse}. Returned by {@code POST /api/servers/discovery}.
 */
export interface DiscoveryReport {
  probed: number;
  discovered: number;
  alreadyKnown: number;
  durationMs: number;
  discoveredUrls: string[];
}

/**
 * One row parsed from the public Travian calendar. Persisted independently of
 * {@link Server} — represents an *announced* world that may not yet have a
 * live {@code map.sql} URL.
 */
export interface UpcomingServer {
  id: number;
  calendarId: string;
  region: string;
  worldName: string;
  speed: number | null;
  scheduledStartAt: string | null;
  status: 'ANNOUNCED' | 'OPEN_REGISTRATION' | 'RUNNING' | 'ENDED' | 'UNKNOWN';
  sourceUrl: string | null;
  inferredServerUrl: string | null;
  linkedServerId: number | null;
  firstSeenAt: string;
  lastSeenAt: string;
}

/** Summary returned by the manual calendar-refresh trigger. */
export interface CalendarScrapeReport {
  parsed: number;
  created: number;
  updated: number;
  durationMs: number;
}
