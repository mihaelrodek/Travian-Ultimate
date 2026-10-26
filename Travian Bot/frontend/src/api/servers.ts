import { api } from './client';
import type {
  CalendarScrapeReport,
  CreateServerRequest,
  DiscoveryReport,
  PivotVillage,
  Server,
  UpcomingServer,
  UpdateServerRequest,
} from '../types/server';

/**
 * REST client for the server-management module. Mirrors the shape of
 * {@code villagesApi} / {@code accountsApi} so the page hook code feels
 * familiar.
 *
 * The {@link seedFromAccounts} call is a one-shot migration helper — it walks
 * the existing accounts table and creates a {@link Server} row for every
 * distinct server URL that doesn't already have one. Safe to re-run.
 *
 * {@link listVillages} returns every village across every local account that
 * targets the given server, so the intel + cropper "near my village" pivot
 * picker can offer the full set without the user having to switch accounts.
 */
export const serversApi = {
  list: () => api.get<Server[]>('/servers'),
  get: (id: number) => api.get<Server>(`/servers/${id}`),
  create: (body: CreateServerRequest) => api.post<Server>('/servers', body),
  update: (id: number, body: UpdateServerRequest) => api.put<Server>(`/servers/${id}`, body),
  remove: (id: number) => api.delete<void>(`/servers/${id}`),
  /** Walk the accounts table and create Server rows for any unknown URLs. */
  seedFromAccounts: () => api.post<{ created: number }>('/servers/seed-from-accounts'),
  /** Every village across every account that uses this server. */
  listVillages: (serverId: number) =>
    api.get<PivotVillage[]>(`/servers/${serverId}/villages`),
  /**
   * Probe every candidate Travian URL and persist new live servers as
   * {@code active=false}. Blocking — takes 30-60s. Manual UI-triggered only.
   */
  discoverAll: (): Promise<DiscoveryReport> =>
    api.post<DiscoveryReport>('/servers/discovery'),
  /** Upcoming launches from the public calendar. */
  listUpcoming: (statuses?: string[]): Promise<UpcomingServer[]> => {
    const qs = statuses && statuses.length > 0 ? `?status=${encodeURIComponent(statuses.join(','))}` : '';
    return api.get<UpcomingServer[]>(`/servers/calendar/upcoming${qs}`);
  },
  /** Trigger a fresh calendar scrape (Playwright). Blocking — a few seconds. */
  refreshCalendar: (): Promise<CalendarScrapeReport> =>
    api.post<CalendarScrapeReport>('/servers/calendar/refresh'),
};
