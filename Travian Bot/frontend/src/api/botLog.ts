import { api } from './client';
import type { BotEventOutcome, BotEventPage } from '../types/botLog';

function query(accountId?: number, outcome?: BotEventOutcome, page?: number, size?: number): string {
  const params = new URLSearchParams();
  if (accountId != null) params.set('accountId', String(accountId));
  if (outcome) params.set('outcome', outcome);
  if (page != null) params.set('page', String(page));
  if (size != null) params.set('size', String(size));
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export const botLogApi = {
  list: (accountId?: number, outcome?: BotEventOutcome, page = 0, size = 50) =>
    api.get<BotEventPage>(`/bot-log${query(accountId, outcome, page, size)}`),
};
