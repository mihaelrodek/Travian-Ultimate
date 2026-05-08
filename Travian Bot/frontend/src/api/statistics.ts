import { api } from './client';
import type { HistoryResponse, StatCategory, Top10Response } from '../types/statistics';

/**
 * Statistics API. Every call is scoped to one {@link StatCategory} tab on the
 * frontend (PVP / PVE / DEFENSE / RAIDERS) — the server side captures all
 * four from a single page fetch, but the read endpoints still return one
 * category at a time so tabs can load independently.
 */
export const statisticsApi = {
  top10: (accountId: number, category: StatCategory) =>
    api.get<Top10Response>(`/statistics/top10?accountId=${accountId}&category=${category}`),

  history: (accountId: number, category: StatCategory, hours: number, playerName?: string) => {
    const player = playerName ? `&player=${encodeURIComponent(playerName)}` : '';
    return api.get<HistoryResponse>(
      `/statistics/history?accountId=${accountId}&category=${category}&hours=${hours}${player}`,
    );
  },

  /**
   * Force an immediate capture. Returns the refreshed snapshot for every
   * category in one go so the UI can update all four tabs without fanout.
   */
  capture: (accountId: number) =>
    api.post<Record<StatCategory, Top10Response>>(`/statistics/capture?accountId=${accountId}`),

  /**
   * Keeps the backend scheduler interested in this account — captures won't
   * happen on the hour unless somebody is looking at the stats page.
   */
  heartbeat: (accountId: number) =>
    api.post<void>(`/statistics/heartbeat?accountId=${accountId}`),
};
