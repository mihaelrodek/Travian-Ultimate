import { api } from './client';
import type {
  AttackPlan,
  AttackPlanMatrix,
  AttackPlanRequest,
} from '../types/attackPlan';

const base = (accountId: number) => `/accounts/${accountId}/attack-plans`;

export const attackPlansApi = {
  list: (accountId: number) => api.get<AttackPlan[]>(base(accountId)),
  get: (accountId: number, id: number) =>
    api.get<AttackPlan>(`${base(accountId)}/${id}`),
  create: (accountId: number, body: AttackPlanRequest) =>
    api.post<AttackPlan>(base(accountId), body),
  update: (accountId: number, id: number, body: AttackPlanRequest) =>
    api.put<AttackPlan>(`${base(accountId)}/${id}`, body),
  delete: (accountId: number, id: number) =>
    api.delete<void>(`${base(accountId)}/${id}`),
  matrix: (accountId: number, id: number) =>
    api.get<AttackPlanMatrix>(`${base(accountId)}/${id}/matrix`),
};
