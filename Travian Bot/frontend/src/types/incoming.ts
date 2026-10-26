/**
 * One incoming attack wave landing on one of our villages, as reported by
 * the rally-point page. Mirrors {@code IncomingAttackResponse} on the
 * backend.
 *
 * Coordinate / speed / class fields are nullable because the rally-point
 * page sometimes only shows the attacker's name (no map link), in which
 * case we can't derive distance and therefore can't classify the troop
 * speed bucket.
 */
export interface IncomingAttack {
  id: number;
  accountId: number;
  targetVillageId: number;
  sourceX: number | null;
  sourceY: number | null;
  sourceVillageName: string | null;
  attackerName: string | null;
  attackerAlliance: string | null;
  /** ISO-8601 instant — when the wave lands. */
  arrivalAt: string;
  /** ISO-8601 instant — when we scraped it. */
  detectedAt: string;
  distanceFields: number | null;
  estimatedSpeedFieldsPerHour: number | null;
  /**
   * Bucket name: "scouts/cav", "cavalry", "raiders", "infantry",
   * "rams/cats", or "unknown".
   */
  estimatedTroopClass: string | null;
  /**
   * Top candidate unit name from the Tournament-Square inverse solver
   * (`TournamentSquareAnalyzer`). Null when we don't have enough information
   * (no source coords, no arrival time, or no candidate within tolerance).
   */
  mostLikelyUnit: string | null;
  /** Top candidate's Tournament Square level, 0..20. */
  mostLikelyTsLevel: number | null;
  /** Raw observation in fields/hour (distance / travel-hours). */
  observedFieldsPerHour: number | null;
  /**
   * Full `AnalysisResult` serialised as JSON. Parsed client-side to render
   * the expandable details cell — kept as a raw string so a missing field
   * (or oversized payload) doesn't break the list response.
   */
  analysisJson: string | null;
  /**
   * User-supplied speed-artifact multiplier. `null` means "no override" —
   * the analyzer assumes no artifact (1.0). When the user picks one in the
   * UI it must match one of {@link ARTIFACT_MULTIPLIERS}.
   */
  manualArtifactMultiplier: number | null;
  waveCount: number;
  /** false means raid (yellow icon), true means attack (red icon). */
  isAttack: boolean;
  acknowledged: boolean;
  /**
   * Backend-computed countdown at response time — the frontend uses it as
   * the initial value for its per-second live ticker, so the user doesn't
   * see a flash of clock-skew if the server time differs from the client.
   */
  secondsUntilArrival: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * One planner-generated "snipe the attacker's empty village" suggestion.
 * The bot will NOT auto-launch — the user takes this as a hint and clicks
 * Send themselves in-game.
 */
/**
 * One scored {(unit, TS level, boots, artifact)} hypothesis from the
 * Tournament-Square inverse solver. Mirrors the backend
 * `AnalysisResult.Candidate` record.
 */
export interface AttackAnalysisCandidate {
  tribeId: number;
  tIndex: number;
  unitName: string;
  role: string;
  tsLevel: number;
  /** Additive bonus: 0 / 0.25 / 0.5 / 0.75 — i.e. speed × (1 + bootsBonus). */
  bootsBonus: number;
  /**
   * Direct multiplier matching the in-game speed-artifact picker
   * (0.33 / 0.5 / 0.67 / 1.0 / 1.5 / 2.0 / 3.0). 1.0 means "no artifact".
   * The solver never iterates over this value — it carries whatever the
   * user manually picked (or 1.0 by default).
   */
  artifactMultiplier: number;
  /** Theoretical travel time in seconds for this combo. */
  theoreticalTime: number;
  /** Fractional deviation from observed time (e.g. 0.012 → 1.2%). */
  deviation: number;
  /** Count of non-zero modifiers (boots, artifact != 1.0, ts > 0). Lower is simpler. */
  modifierCount: number;
}

/**
 * Legal speed-artifact multipliers, matching the in-game picker. Each entry
 * carries both the numeric value (sent to the backend) and the label shown
 * to the user. The sentinel "—" stands for "no artifact" (multiplier 1.0).
 */
export const ARTIFACT_MULTIPLIERS: { value: number; label: string }[] = [
  { value: 3.0, label: '3x' },
  { value: 2.0, label: '2x' },
  { value: 1.5, label: '1.5x' },
  { value: 1.0, label: '—' },
  { value: 0.67, label: '0.67x' },
  { value: 0.5, label: '0.5x' },
  { value: 0.33, label: '0.33x' },
];

/**
 * Parsed shape of the `analysisJson` blob carried on each IncomingAttack
 * row. Mirrors the backend `AnalysisResult` record.
 */
export interface AttackAnalysis {
  observedFieldsPerHour: number;
  candidates: AttackAnalysisCandidate[];
  summary: string;
}

export interface ReturnCatchSuggestion {
  attackId: number;
  fromVillageId: number;
  fromVillageName: string;
  toX: number;
  toY: number;
  attackerArrivesHomeAt: string;
  launchAt: string;
  travelHours: number;
  distance: number;
  reason: string;
}
