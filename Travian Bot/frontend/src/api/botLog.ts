import { api } from './client';
import type { BotEventOutcome, BotEventPage } from '../types/botLog';

/**
 * Filters for GET /bot-log. All optional; they compose with AND on the
 * backend. `from`/`to` are ISO-8601 instants (use Date.toISOString()).
 * `q` is a case-insensitive substring match on message + action code.
 */
export interface BotLogQuery {
  accountId?: number;
  outcome?: BotEventOutcome;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
  size?: number;
}

function query(f: BotLogQuery): string {
  const params = new URLSearchParams();
  if (f.accountId != null) params.set('accountId', String(f.accountId));
  if (f.outcome) params.set('outcome', f.outcome);
  if (f.from) params.set('from', f.from);
  if (f.to) params.set('to', f.to);
  if (f.q && f.q.trim() !== '') params.set('q', f.q.trim());
  if (f.page != null) params.set('page', String(f.page));
  if (f.size != null) params.set('size', String(f.size));
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export const botLogApi = {
  list: (filters: BotLogQuery) => api.get<BotEventPage>(`/bot-log${query(filters)}`),
};
