/**
 * Singleton config for the Telegram integration. Mirrors the backend
 * `TelegramSettingsResponse` 1:1. `configured` is a derived "bot token AND
 * chat id both set" flag the page uses to gate the dependent inputs and the
 * Send-test button.
 */
export interface TelegramSettings {
  enabled: boolean;
  botToken: string | null;
  chatId: string | null;
  notifyIncomingAttack: boolean;
  notifyIncomingRaid: boolean;
  notifyReturnCatch: boolean;
  notifyCaptcha: boolean;
  notifySessionLost: boolean;
  notifyResourceOverflow: boolean;
  updatedAt: string;
  configured: boolean;
}

/** PUT payload. Mirrors `TelegramSettingsRequest`. `updatedAt` + `configured` are server-managed. */
export type TelegramSettingsUpdate = Omit<TelegramSettings, 'updatedAt' | 'configured'>;

/** Result of POST /api/telegram/test. */
export interface TelegramTestResult {
  ok: boolean;
  message: string;
}
