export const TASK_STATUSES = ['PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED'] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

/**
 * Canonical bot task types. Free-form on the backend so the user can coin their own
 * while we're still figuring out the shape. Frontend dropdown uses this list.
 */
export const TASK_TYPES = [
  'LOGIN',
  'FARM_RUN',
  'BUILD_UPGRADE',
  'HERO_ADVENTURE',
  'OASIS_FARM',
  'NPC_BALANCE',
  'CUSTOM',
] as const;

export type TaskType = (typeof TASK_TYPES)[number];

export interface BotTask {
  id: number;
  accountId: number;
  type: string;
  description: string | null;
  status: TaskStatus;
  scheduledAt: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  resultMessage: string | null;
}

export interface BotTaskCreate {
  accountId: number;
  type: string;
  description: string | null;
  scheduledAt: string | null;
}

export interface BotTaskUpdate {
  type: string;
  description: string | null;
  scheduledAt: string | null;
  status?: TaskStatus;
  resultMessage?: string | null;
}
