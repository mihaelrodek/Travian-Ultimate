/**
 * Wire shapes for the war-op planner. Mirror the backend
 * com.travianbot.attackplan.api.dto package 1:1.
 */

export interface AttackPlanTarget {
  id: number;
  x: number;
  y: number;
  label: string | null;
  orderIndex: number;
}

export interface AttackPlanSource {
  id: number;
  villageId: number;
  orderIndex: number;
}

export interface AttackPlan {
  id: number;
  accountId: number;
  name: string;
  /**
   * User-typed unit base speed in fields-per-hour at server-speed x1. The
   * matrix endpoint multiplies by the server multiplier — clients do not
   * have to.
   */
  speedFieldsPerHour: number;
  /** When troops should LAND on the target, ISO-8601 UTC. Null = no landing constraint yet. */
  landingAt: string | null;
  createdAt: string;
  updatedAt: string;
  targets: AttackPlanTarget[];
  sources: AttackPlanSource[];
}

export interface AttackPlanRequest {
  name: string;
  speedFieldsPerHour: number;
  landingAt: string | null;
  targets: Array<{ x: number; y: number; label: string | null }>;
  sourceVillageIds: number[];
}

export interface MatrixSourceRow {
  villageId: number;
  villageName: string;
  x: number;
  y: number;
}

export interface MatrixTargetRow {
  targetId: number;
  x: number;
  y: number;
  label: string | null;
}

export interface MatrixCell {
  distance: number;
  travelSeconds: number;
  /** ISO-8601 UTC, or null when the plan has no landingAt. */
  launchAt: string | null;
  /**
   * Seconds the launch is already in the past. Positive = too late, negative
   * = still launchable. Null when launchAt is null.
   */
  lateBySeconds: number | null;
  /**
   * 1-based wave number within this cell's target column. Wave 1 = the
   * source that has to send FIRST so it lands first (Travian breaks
   * same-instant landing ties by send order). Null when the plan has no
   * landingAt.
   */
  sendOrder: number | null;
}

export interface AttackPlanMatrix {
  planId: number;
  planName: string;
  landingAt: string | null;
  baseSpeedFieldsPerHour: number;
  serverSpeedMultiplier: number;
  effectiveSpeed: number;
  sources: MatrixSourceRow[];
  targets: MatrixTargetRow[];
  rows: MatrixCell[][];
}
