/**
 * One entry in the bot activity log. The backend writes a row whenever a
 * visible action happens (scrape, build start, farmlist send, login, …) so
 * the user can audit what the bot has been up to.
 */
export const BOT_EVENT_OUTCOMES = ['OK', 'WARN', 'ERROR'] as const;
export type BotEventOutcome = (typeof BOT_EVENT_OUTCOMES)[number];

export interface BotEvent {
  id: number;
  accountId: number;
  villageId: number | null;
  action: string;
  outcome: BotEventOutcome;
  message: string | null;
  createdAt: string;
}

/** Pagination envelope returned by {@code GET /api/bot-log}. */
export interface BotEventPage {
  items: BotEvent[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}
