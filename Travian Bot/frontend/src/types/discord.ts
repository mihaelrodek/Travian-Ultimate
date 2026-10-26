/**
 * Singleton config for the Discord integration. Mirrors the backend
 * `DiscordSettingsResponse` 1:1. `configured` is a derived "webhook URL is
 * non-blank" flag the page uses to disable the Send-test button without
 * re-implementing the check.
 */
export interface DiscordSettings {
  enabled: boolean;
  webhookUrl: string | null;
  botUsername: string | null;
  botAvatarUrl: string | null;
  mentionUserId: string | null;
  mentionRoleId: string | null;
  notifyIncomingAttack: boolean;
  notifyIncomingRaid: boolean;
  notifyReturnCatch: boolean;
  notifyCaptcha: boolean;
  notifySessionLost: boolean;
  notifyResourceOverflow: boolean;
  updatedAt: string;
  configured: boolean;
}

/** PUT payload. Mirrors `DiscordSettingsRequest`. `updatedAt` + `configured` are server-managed. */
export type DiscordSettingsUpdate = Omit<DiscordSettings, 'updatedAt' | 'configured'>;

/** Result of POST /api/discord/test. */
export interface DiscordTestResult {
  ok: boolean;
  message: string;
}
