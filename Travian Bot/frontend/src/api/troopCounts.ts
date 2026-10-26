import { api } from './client';
import type { TroopImportSummary, VillageTroopCount } from '../types/troopCount';

/**
 * HTTP client for the per-village troop-count snapshots feature. Matches
 * the backend's `/api/accounts/{accountId}/troop-counts/...` routes.
 */
export const troopCountApi = {
  /** Latest snapshot per village for this account. */
  list: (accountId: number) =>
    api.get<VillageTroopCount[]>(`/accounts/${accountId}/troop-counts`),

  /**
   * Manual paste-in import. Body is whatever the user copied from their
   * Village overview > Troops > Troops in villages tab (Ctrl+A → Ctrl+C → paste).
   */
  importText: (accountId: number, text: string) =>
    api.post<TroopImportSummary>(
      `/accounts/${accountId}/troop-counts/import-text`,
      { text },
    ),

  /** Hard reset — drop every snapshot for the account. */
  clear: (accountId: number) =>
    api.post<{ removed: number }>(`/accounts/${accountId}/troop-counts/clear`),
};
