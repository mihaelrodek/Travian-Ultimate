/**
 * Travian Legends tribe + troop reference catalogue.
 *
 * Each tribe has 10 troop slots (t1..t10). The global unit id (u1..u80) is
 * derived from `(tribeId - 1) * 10 + tIndex`, so:
 *   - Romans   (1):  u1..u10
 *   - Teutons  (2):  u11..u20
 *   - Gauls    (3):  u21..u30
 *   - Nature   (4):  u31..u40   — animals; skipped here
 *   - Natars   (5):  u41..u50
 *   - Egyptians(6):  u51..u60
 *   - Huns     (7):  u61..u70
 *   - Spartans (8):  u71..u80
 *
 * Roles are coarse-grained tags so we can pick the right unit for a use case
 * (scout / settler / chief / ram / cat) without hardcoding per-tribe names.
 *
 * The rally-point form names its inputs `troop[t1]`..`troop[t10]` — the
 * **t-index is 1..10 WITHIN a tribe**, NOT the global u-id. So when we
 * pre-fill a scout request URL we use the per-tribe scout's `tIndex`.
 */

export type TroopRole =
  | 'inf_def'
  | 'inf_off'
  | 'cav_off'
  | 'cav_def'
  | 'scout'
  | 'ram'
  | 'cat'
  | 'chief'
  | 'settler';

export interface Troop {
  /** 1..10 — used in rally-point form field `troop[tN]`. */
  tIndex: number;
  /** Global Travian unit id (1..80). */
  unitId: number;
  name: string;
  role: TroopRole;
  /**
   * Base travel speed in fields-per-hour at server-speed x1, no modifiers.
   * Mirrors the backend `TroopSpeedCatalog` so the UI can render the value
   * next to an analyzer breakdown without an extra round-trip. `null` when
   * the speed isn't catalogued (Natars are sketched in but not used by the
   * Tournament-Square solver — see backend catalog javadoc).
   */
  baseFieldsPerHour: number | null;
}

export interface Tribe {
  id: number;
  /** Lowercase short key (matches map.sql tribeId mapping conventions). */
  key: string;
  /** Display name shown in UI. */
  name: string;
  troops: Troop[];
}

/**
 * Build helper — keeps the per-tribe array compact in source while still
 * computing the right global u-id for each row.
 */
function tribeOf(
  id: number,
  key: string,
  name: string,
  names: Array<{ name: string; role: TroopRole; baseFieldsPerHour?: number | null }>,
): Tribe {
  return {
    id,
    key,
    name,
    troops: names.map((t, i) => ({
      tIndex: i + 1,
      unitId: (id - 1) * 10 + i + 1,
      name: t.name,
      role: t.role,
      baseFieldsPerHour:
        t.baseFieldsPerHour == null ? null : t.baseFieldsPerHour,
    })),
  };
}

export const TRIBES: Tribe[] = [
  tribeOf(1, 'romans', 'Romans', [
    { name: 'Legionnaire',          role: 'inf_def',  baseFieldsPerHour: 6 },
    { name: 'Praetorian',           role: 'inf_def',  baseFieldsPerHour: 5 },
    { name: 'Imperian',             role: 'inf_off',  baseFieldsPerHour: 7 },
    { name: 'Equites Legati',       role: 'scout',    baseFieldsPerHour: 16 },
    { name: 'Equites Imperatoris',  role: 'cav_off',  baseFieldsPerHour: 14 },
    { name: 'Equites Caesaris',     role: 'cav_off',  baseFieldsPerHour: 10 },
    { name: 'Battering Ram',        role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Fire Catapult',        role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Senator',              role: 'chief',    baseFieldsPerHour: 4 },
    { name: 'Settler',              role: 'settler',  baseFieldsPerHour: 5 },
  ]),
  tribeOf(2, 'teutons', 'Teutons', [
    { name: 'Clubswinger',     role: 'inf_off',  baseFieldsPerHour: 7 },
    { name: 'Spearman',        role: 'inf_def',  baseFieldsPerHour: 7 },
    { name: 'Axeman',          role: 'inf_off',  baseFieldsPerHour: 6 },
    { name: 'Scout',           role: 'scout',    baseFieldsPerHour: 9 },
    { name: 'Paladin',         role: 'cav_def',  baseFieldsPerHour: 10 },
    { name: 'Teutonic Knight', role: 'cav_off',  baseFieldsPerHour: 9 },
    { name: 'Ram',             role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Catapult',        role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Chief',           role: 'chief',    baseFieldsPerHour: 4 },
    { name: 'Settler',         role: 'settler',  baseFieldsPerHour: 5 },
  ]),
  tribeOf(3, 'gauls', 'Gauls', [
    { name: 'Phalanx',           role: 'inf_def',  baseFieldsPerHour: 7 },
    { name: 'Swordsman',         role: 'inf_off',  baseFieldsPerHour: 6 },
    { name: 'Pathfinder',        role: 'scout',    baseFieldsPerHour: 17 },
    { name: 'Theutates Thunder', role: 'cav_off',  baseFieldsPerHour: 19 },
    { name: 'Druidrider',        role: 'cav_def',  baseFieldsPerHour: 16 },
    { name: 'Haeduan',           role: 'cav_off',  baseFieldsPerHour: 13 },
    { name: 'Ram',               role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Trebuchet',         role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Chieftain',         role: 'chief',    baseFieldsPerHour: 5 },
    { name: 'Settler',           role: 'settler',  baseFieldsPerHour: 5 },
  ]),
  // Nature (tribe 4) is animals only — never a target, never a scouter.
  // Natars (tribe 5) are not catalogued for travel-speed analysis — they
  // only attack from WW villages with known fixed compositions, so the
  // inverse solver intentionally skips them. Roles below are kept for the
  // rally-point form filler that still needs the t-index.
  tribeOf(5, 'natars', 'Natars', [
    { name: 'Pikeman',         role: 'inf_def',  baseFieldsPerHour: null },
    { name: 'Thorned Warrior', role: 'inf_def',  baseFieldsPerHour: null },
    { name: 'Guardsman',       role: 'inf_def',  baseFieldsPerHour: null },
    { name: 'Birds of Prey',   role: 'scout',    baseFieldsPerHour: null },
    { name: 'Axerider',        role: 'cav_off',  baseFieldsPerHour: null },
    { name: 'Natarian Knight', role: 'cav_off',  baseFieldsPerHour: null },
    { name: 'Warelephant',     role: 'ram',      baseFieldsPerHour: null },
    { name: 'Ballista',        role: 'cat',      baseFieldsPerHour: null },
    { name: 'Emperor',         role: 'chief',    baseFieldsPerHour: null },
    { name: 'Settler',         role: 'settler',  baseFieldsPerHour: null },
  ]),
  tribeOf(6, 'egyptians', 'Egyptians', [
    { name: 'Slave Militia',    role: 'inf_def',  baseFieldsPerHour: 7 },
    { name: 'Ash Warden',       role: 'inf_def',  baseFieldsPerHour: 6 },
    { name: 'Khopesh Warrior',  role: 'inf_off',  baseFieldsPerHour: 7 },
    { name: 'Sopdu Explorer',   role: 'scout',    baseFieldsPerHour: 16 },
    { name: 'Anhur Guard',      role: 'cav_def',  baseFieldsPerHour: 10 },
    { name: 'Resheph Chariot',  role: 'cav_off',  baseFieldsPerHour: 10 },
    { name: 'Ram',              role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Stone Catapult',   role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Nomarch',          role: 'chief',    baseFieldsPerHour: 4 },
    { name: 'Settler',          role: 'settler',  baseFieldsPerHour: 5 },
  ]),
  tribeOf(7, 'huns', 'Huns', [
    { name: 'Mercenary',    role: 'inf_off',  baseFieldsPerHour: 6 },
    { name: 'Bowman',       role: 'inf_off',  baseFieldsPerHour: 6 },
    { name: 'Spotter',      role: 'scout',    baseFieldsPerHour: 19 },
    { name: 'Steppe Rider', role: 'cav_off',  baseFieldsPerHour: 16 },
    { name: 'Marksman',     role: 'cav_off',  baseFieldsPerHour: 14 },
    { name: 'Marauder',     role: 'cav_off',  baseFieldsPerHour: 11 },
    { name: 'Ram',          role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Catapult',     role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Logades',      role: 'chief',    baseFieldsPerHour: 4 },
    { name: 'Settler',      role: 'settler',  baseFieldsPerHour: 5 },
  ]),
  tribeOf(8, 'spartans', 'Spartans', [
    { name: 'Hoplite',             role: 'inf_def',  baseFieldsPerHour: 7 },
    { name: 'Sentinel',            role: 'inf_def',  baseFieldsPerHour: 9 },
    { name: 'Shieldsman',          role: 'inf_off',  baseFieldsPerHour: 7 },
    { name: 'Twinhorn Warrior',    role: 'scout',    baseFieldsPerHour: 16 },
    { name: 'Elpida Rider',        role: 'cav_def',  baseFieldsPerHour: 13 },
    { name: 'Corinthian Crusher',  role: 'cav_off',  baseFieldsPerHour: 10 },
    { name: 'Ram',                 role: 'ram',      baseFieldsPerHour: 4 },
    { name: 'Ballista',            role: 'cat',      baseFieldsPerHour: 3 },
    { name: 'Ephor',               role: 'chief',    baseFieldsPerHour: 4 },
    { name: 'Settler',             role: 'settler',  baseFieldsPerHour: 5 },
  ]),
];

const TRIBES_BY_ID = new Map<number, Tribe>(TRIBES.map((t) => [t.id, t]));

export function getTribe(tribeId: number | null | undefined): Tribe | null {
  if (tribeId == null) return null;
  return TRIBES_BY_ID.get(tribeId) ?? null;
}

export function getTribeName(tribeId: number | null | undefined): string {
  return getTribe(tribeId)?.name ?? (tribeId == null ? 'Unknown' : `Tribe ${tribeId}`);
}

/** Pick the scout troop for a tribe. Returns null for tribes without a scout (Nature). */
export function getScoutTroop(tribeId: number | null | undefined): Troop | null {
  const tribe = getTribe(tribeId);
  if (!tribe) return null;
  return tribe.troops.find((t) => t.role === 'scout') ?? null;
}

/** Convenience for any role lookup. */
export function getTroopByRole(tribeId: number | null | undefined, role: TroopRole): Troop | null {
  const tribe = getTribe(tribeId);
  if (!tribe) return null;
  return tribe.troops.find((t) => t.role === role) ?? null;
}
