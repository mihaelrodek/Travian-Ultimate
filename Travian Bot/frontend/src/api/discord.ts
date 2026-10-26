import { api } from './client';
import type {
  DiscordSettings,
  DiscordSettingsUpdate,
  DiscordTestResult,
} from '../types/discord';

/**
 * HTTP client for the Discord integration. Backend mounts under
 * `/api/discord` (not scoped per-account — Discord config is global).
 */
export const discordApi = {
  /** Latest config. Lazy-creates the row on first call. */
  get: () => api.get<DiscordSettings>('/discord/settings'),

  /** Replace the whole config row. */
  update: (body: DiscordSettingsUpdate) =>
    api.put<DiscordSettings>('/discord/settings', body),

  /**
   * Synchronously POST a small "test" message to the saved webhook. Bypasses
   * the master switch + per-event toggles — the whole point is to verify
   * the URL while everything else is still off.
   */
  test: () => api.post<DiscordTestResult>('/discord/test'),
};
