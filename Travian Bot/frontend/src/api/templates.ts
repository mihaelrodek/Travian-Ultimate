import { api } from './client';
import type {
  ApplyResponse,
  CsvImportRequest,
  VillageTemplate,
  VillageTemplateCreate,
  VillageTemplateUpdate,
} from '../types/template';

export const templatesApi = {
  list: (accountId: number) =>
    api.get<VillageTemplate[]>(`/templates?accountId=${accountId}`),
  get: (id: number) => api.get<VillageTemplate>(`/templates/${id}`),
  create: (body: VillageTemplateCreate) => api.post<VillageTemplate>('/templates', body),
  update: (id: number, body: VillageTemplateUpdate) => api.put<VillageTemplate>(`/templates/${id}`, body),
  delete: (id: number) => api.delete<void>(`/templates/${id}`),
  /**
   * Appends the template's steps onto the given village's build queue.
   * Returns the number of rows added. Caller is expected to re-fetch the
   * village to see the new queue state.
   */
  apply: (id: number, villageId: number) =>
    api.post<ApplyResponse>(`/templates/${id}/apply?villageId=${villageId}`),
  importCsv: (body: CsvImportRequest) => api.post<VillageTemplate>('/templates/import-csv', body),
  /**
   * Fetches the CSV body for a template. Returned as a string since our
   * {@code api} wrapper JSON-parses by default — this endpoint serves
   * {@code text/csv} and needs manual handling via the raw fetch below.
   */
  exportCsv: async (id: number): Promise<string> => {
    const res = await fetch(`/api/templates/${id}/export-csv`, {
      method: 'GET',
      credentials: 'include',
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.text();
  },
};
