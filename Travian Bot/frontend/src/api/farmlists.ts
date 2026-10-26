import { api } from './client';
import type {
  Farmlist,
  FarmlistCreate,
  FarmlistTargetMetric,
  FarmlistUpdate,
  RaidReport,
  ScrapeFarmlistsResponse,
  ScrapeRaidReportsResponse,
  SendAllFarmlistsResponse,
  SendListResponse,
} from '../types/farmlist';

export const farmlistsApi = {
  list: (accountId?: number) =>
    api.get<Farmlist[]>(accountId != null ? `/farmlists?accountId=${accountId}` : '/farmlists'),
  get: (id: number) => api.get<Farmlist>(`/farmlists/${id}`),
  create: (body: FarmlistCreate) => api.post<Farmlist>('/farmlists', body),
  update: (id: number, body: FarmlistUpdate) => api.put<Farmlist>(`/farmlists/${id}`, body),
  delete: (id: number) => api.delete<void>(`/farmlists/${id}`),

  /** Run the rally-point farmlists tab scraper for this account right now. */
  scrape: (accountId: number) =>
    api.post<ScrapeFarmlistsResponse>(`/accounts/${accountId}/scrape-farmlists`),

  /** Fire rally-point "send all" on this single farmlist right now. */
  sendAll: (farmlistId: number) =>
    api.post<SendListResponse>(`/farmlists/${farmlistId}/send-all`),

  /** Fire "send all" on every enabled farmlist for this account. */
  sendAllForAccount: (accountId: number) =>
    api.post<SendAllFarmlistsResponse>(`/accounts/${accountId}/send-all-farmlists`),

  // ---- analytics ----
  // The metrics + reports endpoints are read-only views over the analyzer
  // rollup. Triggering a scrape is a side effect on the account-wide raid
  // report scraper, not specific to a single farmlist.

  /** All targets on the list with their (zero-defaulted) metric rows. */
  getFarmlistMetrics: (accountId: number, farmlistId: number) =>
    api.get<FarmlistTargetMetric[]>(
      `/accounts/${accountId}/farmlists/${farmlistId}/analytics/metrics`,
    ),

  /** Target ids the analyzer currently flags as auto-prune candidates. */
  getPruneCandidates: (accountId: number, farmlistId: number) =>
    api.get<number[]>(
      `/accounts/${accountId}/farmlists/${farmlistId}/analytics/prune-candidates`,
    ),

  /** Fire an immediate raid-report scrape for the account. */
  triggerRaidReportScrape: (accountId: number) =>
    api.post<ScrapeRaidReportsResponse>(`/accounts/${accountId}/raid-reports/scrape`),

  /** Reports attributed to a single target, latest first. */
  listTargetReports: (accountId: number, farmlistTargetId: number) =>
    api.get<RaidReport[]>(
      `/accounts/${accountId}/raid-reports/by-target/${farmlistTargetId}`,
    ),
};
