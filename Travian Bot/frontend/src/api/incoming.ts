import { api } from './client';
import type { IncomingAttack, ReturnCatchSuggestion } from '../types/incoming';

/**
 * HTTP client for the incoming-attacks feature. Matches the backend's
 * {@code /api/accounts/{accountId}/incoming/...} routes.
 */
export const incomingApi = {
  /** Every recorded incoming on the account, soonest arrival first. */
  list: (accountId: number) =>
    api.get<IncomingAttack[]>(`/accounts/${accountId}/incoming`),

  /**
   * Trigger a one-off scrape now. Returns the count of NEW waves recorded —
   * 0 is a healthy response (no new attacks, scrape worked).
   */
  triggerScrape: (accountId: number) =>
    api.post<{ scraped: number }>(`/accounts/${accountId}/incoming/scrape`),

  /** Flip the row's read flag — removes it from "needs attention" lists. */
  acknowledge: (accountId: number, id: number) =>
    api.post<IncomingAttack>(`/accounts/${accountId}/incoming/${id}/acknowledge`),

  /**
   * Override the speed-artifact multiplier on a single attack row and re-run
   * the TS solver immediately. Pass `null` to clear the override (analyzer
   * then ignores artifacts on its next run).
   */
  setArtifact: (accountId: number, id: number, artifactMultiplier: number | null) =>
    api.post<IncomingAttack>(
      `/accounts/${accountId}/incoming/${id}/artifact`,
      { artifactMultiplier },
    ),

  /**
   * Planner output: when (and from which village) we could launch a
   * return-catch attack on each known attacker.
   */
  listReturnCatch: (accountId: number) =>
    api.get<ReturnCatchSuggestion[]>(`/accounts/${accountId}/incoming/return-catch`),

  /**
   * Manual paste-in import. Body is whatever the user copied from their
   * rally-point Overview tab (Ctrl+A → Ctrl+C → paste). Accepts both plain
   * text and HTML — backend sniffs and routes appropriately.
   */
  importText: (accountId: number, text: string) =>
    api.post<{ imported: number }>(`/accounts/${accountId}/incoming/import-text`, { text }),

  /**
   * Re-run the Tournament-Square inverse solver against every persisted
   * incoming-attack row for the account. Local recompute — no Travian
   * traffic. Useful after the user fills in `Server.speedMultiplier` for a
   * legacy row, or whenever the troop catalog changes.
   */
  reanalyse: (accountId: number) =>
    api.post<{ reanalysed: number }>(`/accounts/${accountId}/incoming/reanalyse`),

  /**
   * Hard reset — delete every recorded incoming for the account. Use after a
   * parser fix lands so the user can re-import without stale wrong-coord
   * rows polluting the table.
   */
  clearAll: (accountId: number) =>
    api.post<{ removed: number }>(`/accounts/${accountId}/incoming/clear`),
};
