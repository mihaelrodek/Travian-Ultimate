import { api } from './client';
import type {
  TelegramSettings,
  TelegramSettingsUpdate,
  TelegramTestResult,
} from '../types/telegram';

/**
 * HTTP client for the Telegram integration. Backend mounts under
 * `/api/telegram` (global config, not per-account) — same shape as Discord.
 */
export const telegramApi = {
  get: () => api.get<TelegramSettings>('/telegram/settings'),
  update: (body: TelegramSettingsUpdate) =>
    api.put<TelegramSettings>('/telegram/settings', body),
  /** Synchronously post a test message to the saved chat. Bypasses the master switch. */
  test: () => api.post<TelegramTestResult>('/telegram/test'),
};
