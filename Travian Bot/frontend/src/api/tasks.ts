import { api } from './client';
import type { BotTask, BotTaskCreate, BotTaskUpdate, TaskStatus } from '../types/task';

function query(accountId?: number, status?: TaskStatus): string {
  const params = new URLSearchParams();
  if (accountId != null) params.set('accountId', String(accountId));
  if (status) params.set('status', status);
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export const tasksApi = {
  list: (accountId?: number, status?: TaskStatus) =>
    api.get<BotTask[]>(`/tasks${query(accountId, status)}`),
  get: (id: number) => api.get<BotTask>(`/tasks/${id}`),
  create: (body: BotTaskCreate) => api.post<BotTask>('/tasks', body),
  update: (id: number, body: BotTaskUpdate) => api.put<BotTask>(`/tasks/${id}`, body),
  delete: (id: number) => api.delete<void>(`/tasks/${id}`),
};
