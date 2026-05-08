import { api } from './client';
import type { Account, CreateAccountRequest, LoginResult, UpdateAccountRequest } from '../types/account';

/**
 * The three "power" actions are deliberately distinct so the UI can express
 * intent precisely:
 *   - {@link shutdown} closes Playwright without touching the game server —
 *     the user just wants the bot to stop.
 *   - {@link inGameLogout} clicks Travian's own Logout link so the server
 *     ends the session as well — useful before deleting an account.
 *   - {@link restart} does a shutdown + login in one call, for the "this
 *     session is wedged, give me a fresh one" flow.
 */
export const accountsApi = {
  list: () => api.get<Account[]>('/accounts'),
  get: (id: number) => api.get<Account>(`/accounts/${id}`),
  create: (body: CreateAccountRequest) => api.post<Account>('/accounts', body),
  update: (id: number, body: UpdateAccountRequest) => api.put<Account>(`/accounts/${id}`, body),
  delete: (id: number) => api.delete<void>(`/accounts/${id}`),
  login: (id: number) => api.post<LoginResult>(`/accounts/${id}/login`),
  shutdown: (id: number) => api.post<LoginResult>(`/accounts/${id}/shutdown`),
  inGameLogout: (id: number) => api.post<LoginResult>(`/accounts/${id}/in-game-logout`),
  restart: (id: number) => api.post<LoginResult>(`/accounts/${id}/restart`),
};
