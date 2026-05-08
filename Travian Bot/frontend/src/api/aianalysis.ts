import { api } from './client';
import type { OasisScan, StartScanRequest } from '../types/aianalysis';

/**
 * AI Analysis (oasis scan) endpoints. The "scan" call is the only mutation —
 * everything else is read-only.
 */
export const aiAnalysisApi = {
  latest: (accountId: number) =>
    api.get<OasisScan | null>(`/aianalysis/${accountId}/latest`),

  history: (accountId: number) =>
    api.get<OasisScan[]>(`/aianalysis/${accountId}/history`),

  scan: (accountId: number, body: StartScanRequest) =>
    api.post<OasisScan>(`/aianalysis/${accountId}/scan`, body),
};
