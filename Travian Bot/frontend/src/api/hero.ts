import { api } from './client';
import type { Hero } from '../types/hero';

export const heroApi = {
  get: (accountId: number) => api.get<Hero>(`/accounts/${accountId}/hero`),
  scrape: (accountId: number) => api.post<Hero>(`/accounts/${accountId}/hero/scrape`),
};
