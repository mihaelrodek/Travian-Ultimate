import { api } from './client';
import type {
  BuildingCatalogEntry,
  BuildingQueueItem,
  Village,
  VillageCreate,
  VillageType,
  VillageUpdate,
} from '../types/village';

export const villagesApi = {
  list: (accountId?: number) =>
    api.get<Village[]>(accountId != null ? `/villages?accountId=${accountId}` : '/villages'),
  get: (id: number) => api.get<Village>(`/villages/${id}`),
  create: (body: VillageCreate) => api.post<Village>('/villages', body),
  update: (id: number, body: VillageUpdate) => api.put<Village>(`/villages/${id}`, body),
  delete: (id: number) => api.delete<void>(`/villages/${id}`),
  replaceQueue: (id: number, items: BuildingQueueItem[]) =>
    api.put<Village>(`/villages/${id}/queue`, { items }),
  /** Scrape this village via the account's live browser session. */
  scrape: (id: number) => api.post<Village>(`/villages/${id}/scrape`),
  /**
   * Kick off the actual in-game upgrade for a queued item. Needs an open,
   * logged-in browser session on the owning account — the API returns 409
   * otherwise.
   */
  startQueueItem: (villageId: number, queueItemId: number) =>
    api.post<Village>(`/villages/${villageId}/queue/${queueItemId}/start`),
  /** Scrape whatever village is currently active in the account's browser. */
  scrapeCurrentForAccount: (accountId: number) =>
    api.post<Village>(`/accounts/${accountId}/scrape-current-village`),

  /**
   * Refreshes every village on the account via its live browser session.
   * Returns the count of villages successfully re-scraped. Requires the
   * account to be logged in (409 otherwise).
   */
  scrapeAllForAccount: (accountId: number) =>
    api.post<{ villagesScraped: number }>(`/accounts/${accountId}/scrape-all-villages`),

  /**
   * Persists a user-chosen ordering for the Villages sidebar. {@code villageIds}
   * is the authoritative new order — position 0 becomes {@code orderIndex=0},
   * etc. Returns the full re-ordered list so the caller can skip a refetch.
   */
  reorder: (accountId: number, villageIds: number[]) =>
    api.post<Village[]>(`/villages/reorder?accountId=${accountId}`, villageIds),

  /**
   * Partial update of the user-applied classification: TS level + village
   * type. Either field may be null to clear. Used by the Village troops
   * page inline editors.
   */
  classify: (
    id: number,
    body: { tsLevel: number | null; villageType: VillageType | null },
  ) => api.patch<Village>(`/villages/${id}/classification`, body),
};

export const buildingCatalogApi = {
  list: () => api.get<BuildingCatalogEntry[]>('/buildings/catalog'),
};
