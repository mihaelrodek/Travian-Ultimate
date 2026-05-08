import { api } from './client';
import type {
  Farmlist,
  FarmlistCreate,
  FarmlistUpdate,
  SendListResponse,
  ScrapeFarmlistsResponse,
  SendAllFarmlistsResponse,
} from '../types/farmlist';

export const farmlistsApi = {
  list: (accountId?: number) =>
    api.get<Farmlist[]>(accountId != null ? `/farmlists?accountId=${accountId}` : '/farmlists'),
  get: (id: number) => api.get<Farmlist>(`/farmlists/${id}`),
  create: (body: FarmlistCreate) => api.post<Farmlist>('/farmlists', body),
  update: (id: number, body: FarmlistUpdate) => api.put<Farmlist>(`/farmlists/${id}`, body),
  delete: (id: number) => api.delete<void>(`/farmlists/${id}`),

  /** Run the rally-point farmlists tab scraper for this account right now. */
  scrape: (accountId: number) =>
    api.post<ScrapeFarmlistsResponse>(`/accounts/${accountId}/scrape-farmlists`),

  /** Fire rally-point "send all" on this single farmlist right now. */
  sendAll: (farmlistId: number) =>
    api.post<SendListResponse>(`/farmlists/${farmlistId}/send-all`),

  /** Fire "send all" on every enabled farmlist for this account. */
  sendAllForAccount: (accountId: number) =>
    api.post<SendAllFarmlistsResponse>(`/accounts/${accountId}/send-all-farmlists`),
};
