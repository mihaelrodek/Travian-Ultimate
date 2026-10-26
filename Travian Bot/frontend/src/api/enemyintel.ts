import { api } from './client';
import type {
  EnemyPlayer,
  EnemyPlayerDetail,
  EnemyProfileSnapshot,
} from '../types/enemyintel';

/**
 * HTTP client for the enemy-intel module. Matches the backend's
 * {@code /api/accounts/{accountId}/enemyintel/...} routes.
 *
 * forceScrapeEnemy returns {@code null} when the backend completed the
 * scrape but couldn't parse any fields off the profile page — same response
 * shape as a successful empty snapshot, distinguished from "got a snapshot"
 * by the body being absent.
 */
export const enemyIntelApi = {
  listEnemies: (accountId: number) =>
    api.get<EnemyPlayer[]>(`/accounts/${accountId}/enemyintel/enemies`),

  getEnemy: (accountId: number, enemyPlayerId: number) =>
    api.get<EnemyPlayerDetail>(
      `/accounts/${accountId}/enemyintel/enemies/${enemyPlayerId}`,
    ),

  /**
   * Returns the saved snapshot, or {@code null} when the scrape ran but the
   * profile page parsed to no useful fields.
   */
  forceScrapeEnemy: async (
    accountId: number,
    enemyPlayerId: number,
  ): Promise<EnemyProfileSnapshot | null> => {
    const res = await api.post<EnemyProfileSnapshot | '' | null>(
      `/accounts/${accountId}/enemyintel/enemies/${enemyPlayerId}/scrape`,
    );
    if (res == null || res === '' || typeof res !== 'object') return null;
    return res as EnemyProfileSnapshot;
  },

  registerFromRecentAttacks: (accountId: number, windowHours?: number) => {
    const qs = windowHours == null ? '' : `?windowHours=${windowHours}`;
    return api.post<{ created: number }>(
      `/accounts/${accountId}/enemyintel/register-from-recent-attacks${qs}`,
    );
  },

  refreshStale: (accountId: number, stalenessHours?: number, max?: number) => {
    const params = new URLSearchParams();
    if (stalenessHours != null) params.set('stalenessHours', String(stalenessHours));
    if (max != null) params.set('max', String(max));
    const qs = params.toString();
    return api.post<{ scraped: number }>(
      `/accounts/${accountId}/enemyintel/refresh-stale${qs ? `?${qs}` : ''}`,
    );
  },
};
