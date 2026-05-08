import { api } from './client';
import type {
  AccountProxy,
  CreateAccountProxyRequest,
  UpdateAccountProxyRequest,
} from '../types/account';

/**
 * CRUD for per-account proxy rotation. Nested under the account URL so the
 * caller never has to scope results — every row returned belongs to the
 * account in the path. Matches {@code AccountProxyController} on the
 * backend.
 */
export const proxiesApi = {
  list: (accountId: number) =>
    api.get<AccountProxy[]>(`/accounts/${accountId}/proxies`),
  create: (accountId: number, body: CreateAccountProxyRequest) =>
    api.post<AccountProxy>(`/accounts/${accountId}/proxies`, body),
  update: (accountId: number, id: number, body: UpdateAccountProxyRequest) =>
    api.put<AccountProxy>(`/accounts/${accountId}/proxies/${id}`, body),
  delete: (accountId: number, id: number) =>
    api.delete<void>(`/accounts/${accountId}/proxies/${id}`),
};
