import { api } from './client';
import type {
  AccountSettings,
  AccountSettingsUpdate,
  GeneralSettings,
} from '../types/settings';
import type { VillageSettings, VillageSettingsUpdate } from '../types/villageSettings';

export const accountSettingsApi = {
  get: (accountId: number) => api.get<AccountSettings>(`/accounts/${accountId}/settings`),
  update: (accountId: number, body: AccountSettingsUpdate) =>
    api.put<AccountSettings>(`/accounts/${accountId}/settings`, body),
};

/**
 * Per-village settings (NPC config + auto-scrape toggle). Sits on
 * {@code /api/villages/{id}/settings}, not under accounts — the server
 * creates defaults on demand so GET is always safe.
 */
export const villageSettingsApi = {
  get: (villageId: number) => api.get<VillageSettings>(`/villages/${villageId}/settings`),
  update: (villageId: number, body: VillageSettingsUpdate) =>
    api.put<VillageSettings>(`/villages/${villageId}/settings`, body),
};

export const generalSettingsApi = {
  get: () => api.get<GeneralSettings>('/settings/general'),
  update: (body: GeneralSettings) => api.put<GeneralSettings>('/settings/general', body),
};
