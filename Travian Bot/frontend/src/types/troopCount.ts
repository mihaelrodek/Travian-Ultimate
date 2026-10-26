/**
 * Latest known troop counts stationed in one of our villages. Mirrors the
 * backend `VillageTroopCountResponse`.
 *
 * Slots `t1..t10` are positional within the tribe (same convention as the
 * rally-point form's `troop[tN]` inputs). `tribeId` lets the UI label them;
 * `null` means the parser couldn't infer the tribe from the paste's header
 * row — counts are still meaningful, just labelled "t1..t10" by index.
 */
export interface VillageTroopCount {
  id: number;
  accountId: number;
  villageId: number;
  tribeId: number | null;
  t1: number;
  t2: number;
  t3: number;
  t4: number;
  t5: number;
  t6: number;
  t7: number;
  t8: number;
  t9: number;
  t10: number;
  hero: number;
  updatedAt: string;
}

/** Result of POST /troop-counts/import-text. */
export interface TroopImportSummary {
  imported: number;
  skipped: number;
  parsed: number;
  tribeId: number | null;
}
