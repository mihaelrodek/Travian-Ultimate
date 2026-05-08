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
