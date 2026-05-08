import { api } from './client';
import type { BackendHealth } from '../types/health';

export const healthApi = {
  get: () => api.get<BackendHealth>('/health'),
};
