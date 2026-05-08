/**
 * The four weekly rankings surfaced on Travian's
 * {@code /statistics/player/top10} page. Names mirror the backend enum so the
 * string goes straight over the wire without translation.
 *
 * - PVP      → "PvP of the week" — attack points vs. other players
 * - PVE      → "PvE of the week" — attack points vs. Natars / bandits
 * - DEFENSE  → "Defenders of the week" — defense points
 * - RAIDERS  → "Robbers of the week" — resources raided
 */
export type StatCategory = 'PVP' | 'PVE' | 'DEFENSE' | 'RAIDERS';

export const STAT_CATEGORIES: StatCategory[] = ['PVP', 'PVE', 'DEFENSE', 'RAIDERS'];

/**
 * One row of a category's top-10 table. Every ranking on the source page has
 * an identical three-column shape (rank, player, value) so the DTO matches.
 * The meaning of {@code score} is fixed by the enclosing {@link Top10Response.category}
 * — it's attack pts for PVP/PVE, defense pts for DEFENSE, raided resources
 * for RAIDERS.
 *
 * <p>{@code scoreDelta} / {@code rankDelta} are nullable ({@code null} = no
 * previous capture to compare). Rank delta is inverted: positive = moved up.
 */
export interface Top10Row {
  rank: number;
  playerName: string;
  score: number | null;
  scoreDelta: number | null;
  rankDelta: number | null;
}

export interface Top10Response {
  accountId: number;
  category: StatCategory;
  serverUrl: string | null;
  capturedAt: string | null;
  previousCapturedAt: string | null;
  rows: Top10Row[];
}

/**
 * One history point plotted on the SVG line chart.
 */
export interface HistoryPoint {
  capturedAt: string;
  rank: number | null;
  score: number | null;
}

export interface HistoryResponse {
  accountId: number;
  category: StatCategory;
  hours: number;
  playerName: string | null;
  points: HistoryPoint[];
}
