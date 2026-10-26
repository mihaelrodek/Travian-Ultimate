import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { troopCountApi } from '../api/troopCounts';
import { villagesApi } from '../api/villages';
import { getTribe, getTribeName } from '../data/travianTroops';
import type { Tribe, TroopRole } from '../data/travianTroops';
import type { VillageTroopCount } from '../types/troopCount';
import type { Village, VillageType } from '../types/village';
import { VILLAGE_TYPES, VILLAGE_TYPE_LABELS } from '../types/village';

/**
 * Bulk-import + view of stationed troop counts per village.
 *
 * Travian doesn't expose this through a stable HTML structure on a single
 * page that we can scrape without firing the village-overview tab, so we
 * use the same Ctrl+A → Ctrl+C → Ctrl+V pattern as the rally-point report.
 * User copies the entire "Village overview > Troops > Troops in villages"
 * page, paste here, parser pins each row to a village by name (or trailing
 * coords for "Natars X|Y"-style names) and upserts the latest counts.
 *
 * Display is one row per village, columns labelled by the inferred tribe
 * (Phalanx, Swordsman, etc. for Gauls; "t1..t10" when tribe inference
 * fails). Villages with no stored snapshot are omitted — they show up
 * only once the user imports their first paste.
 */
export default function TroopCountsPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [counts, setCounts] = useState<VillageTroopCount[]>([]);
  const [villages, setVillages] = useState<Village[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);
  const [pasteText, setPasteText] = useState('');
  const [importing, setImporting] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [flash, setFlash] = useState<
    { kind: 'ok' | 'err'; text: string } | null
  >(null);

  const reload = useCallback(async () => {
    if (accountId == null) {
      setCounts([]);
      setVillages([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [c, v] = await Promise.all([
        troopCountApi.list(accountId),
        villagesApi.list(accountId),
      ]);
      setCounts(c);
      setVillages(v);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load troop counts');
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const onImport = async () => {
    if (accountId == null || !pasteText.trim()) return;
    setImporting(true);
    setFlash(null);
    try {
      const res = await troopCountApi.importText(accountId, pasteText);
      setFlash({
        kind: 'ok',
        text:
          `Imported ${res.imported} village${res.imported === 1 ? '' : 's'}` +
          (res.skipped > 0 ? `, ${res.skipped} skipped (no match)` : '') +
          (res.tribeId != null ? ` — tribe: ${getTribeName(res.tribeId)}` : ''),
      });
      setPasteText('');
      await reload();
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Import failed',
      });
    } finally {
      setImporting(false);
    }
  };

  const onClear = async () => {
    if (accountId == null) return;
    if (!window.confirm('Drop every stored troop snapshot for this account?'))
      return;
    setClearing(true);
    setFlash(null);
    try {
      const res = await troopCountApi.clear(accountId);
      setFlash({
        kind: 'ok',
        text: `Cleared ${res.removed} snapshot${res.removed === 1 ? '' : 's'}.`,
      });
      await reload();
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Clear failed',
      });
    } finally {
      setClearing(false);
    }
  };

  // Pin every snapshot to its village so the table can render name + coords.
  // Index by villageId for O(1) lookup; same-account-only.
  const villagesById = useMemo(() => {
    const m = new Map<number, Village>();
    for (const v of villages) m.set(v.id, v);
    return m;
  }, [villages]);

  const sortedCounts = useMemo(() => {
    return [...counts].sort((a, b) => {
      const av = villagesById.get(a.villageId);
      const bv = villagesById.get(b.villageId);
      const an = av?.name ?? '';
      const bn = bv?.name ?? '';
      return an.localeCompare(bn);
    });
  }, [counts, villagesById]);

  // Tribe detection is per-row (the snapshot carries `tribeId`), but in
  // practice every row on one account shares the same tribe. Use the first
  // non-null tribe to label the table columns.
  const tribeId = useMemo<number | null>(() => {
    for (const c of counts) if (c.tribeId != null) return c.tribeId;
    return null;
  }, [counts]);

  const tribe = getTribe(tribeId);
  const columnLabels = useMemo(() => {
    if (tribe) {
      return tribe.troops.map((t) => t.name);
    }
    // Fallback when we couldn't infer tribe — just label by index.
    return ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8', 't9', 't10'];
  }, [tribe]);

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Vojska po selima"
        subtitle="Paste-import vojske + agregati po roli + tipu sela."
      />
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the{' '}
            <Link to="/accounts" className="link">
              Accounts page
            </Link>{' '}
            to import troop counts.
          </p>
        </Card>
      )}

      {accountId != null && (
        <>
          <Card title="Paste-in import">
            <textarea
              value={pasteText}
              onChange={(e) => setPasteText(e.target.value)}
              placeholder="Paste the troops-in-villages page contents here…"
              rows={6}
              style={{
                width: '100%',
                fontFamily: 'monospace',
                fontSize: '0.85em',
              }}
            />
            <div className="toolbar" style={{ marginTop: '0.5rem' }}>
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={() => void onImport()}
                disabled={importing || !pasteText.trim()}
              >
                {importing ? 'Importing…' : 'Import troops'}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => setPasteText('')}
                disabled={importing || !pasteText}
              >
                Clear textarea
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void onClear()}
                disabled={clearing || sortedCounts.length === 0}
                title="Delete every stored troop snapshot for this account."
                style={{ marginLeft: 'auto' }}
              >
                {clearing ? 'Clearing…' : 'Clear all'}
              </button>
            </div>
            {flash && (
              <p
                className={
                  flash.kind === 'ok'
                    ? 'form__msg form__msg--ok'
                    : 'form__msg form__msg--error'
                }
                style={{ marginTop: '0.5rem' }}
              >
                {flash.text}
              </p>
            )}
          </Card>

          {sortedCounts.length > 0 && (
            <TroopsSummaryPanel
              counts={sortedCounts}
              villages={villages}
              tribe={tribe}
              columnLabels={columnLabels}
            />
          )}

          <Card
            title={`Stored snapshots${tribe ? ` — ${tribe.name}` : ''}`}
            hint={
              sortedCounts.length === 0
                ? undefined
                : `${sortedCounts.length} village${sortedCounts.length === 1 ? '' : 's'} with troop data`
            }
          >
            {error && <p className="form__msg form__msg--error">{error}</p>}

            {sortedCounts.length === 0 && !loading && !error && (
              <p className="muted" style={{ margin: 0 }}>
                Nothing imported yet. Paste a troops table above to populate.
              </p>
            )}

            {sortedCounts.length > 0 && (
              <div style={{ overflowX: 'auto' }}>
                <table className="table">
                  <thead>
                    <tr>
                      <th style={{ minWidth: '12rem' }}>Village</th>
                      <th style={{ minWidth: '7rem' }}>Type</th>
                      <th style={{ minWidth: '4rem' }}>TS</th>
                      {columnLabels.map((label) => (
                        <th key={label} style={{ minWidth: '5rem' }}>
                          {label}
                        </th>
                      ))}
                      <th style={{ minWidth: '4rem' }}>Hero</th>
                      <th style={{ minWidth: '8rem' }}>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedCounts.map((c) => (
                      <TroopRow
                        key={c.id}
                        snapshot={c}
                        village={villagesById.get(c.villageId)}
                        onVillagePatched={(updated) =>
                          setVillages((prev) =>
                            prev.map((v) => (v.id === updated.id ? updated : v)),
                          )
                        }
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}

function TroopRow({
  snapshot,
  village,
  onVillagePatched,
}: {
  snapshot: VillageTroopCount;
  village: Village | undefined;
  onVillagePatched: (updated: Village) => void;
}) {
  // Render zeros as a subtle dash so the busy slots pop visually — easier
  // to spot "this village has 10k Theutates Thunder" at a glance.
  const cell = (n: number) =>
    n === 0 ? (
      <span className="muted">—</span>
    ) : (
      <span style={{ fontFamily: 'monospace' }}>{n.toLocaleString()}</span>
    );

  // Local optimistic state for the two editable fields, so a slow PATCH
  // doesn't cause input flicker. We start from the village row and
  // overwrite the parent's copy on success.
  const [savingType, setSavingType] = useState(false);
  const [savingTs, setSavingTs] = useState(false);
  const [tsDraft, setTsDraft] = useState<string>(
    village?.tsLevel != null ? String(village.tsLevel) : '',
  );

  // Re-sync the TS input when the parent ships in a fresher village (e.g.
  // after another row's mutation rehydrated the list).
  useEffect(() => {
    setTsDraft(village?.tsLevel != null ? String(village.tsLevel) : '');
  }, [village?.tsLevel]);

  const patchClassification = async (
    tsLevel: number | null,
    villageType: VillageType | null,
  ) => {
    if (!village) return;
    try {
      const updated = await villagesApi.classify(village.id, { tsLevel, villageType });
      onVillagePatched(updated);
    } catch (err) {
      // Surface inline — flash banner is owned by the parent and would
      // double-report. Reset draft so the UI doesn't lie about saved state.
      console.warn('Classification patch failed', err);
      setTsDraft(village.tsLevel != null ? String(village.tsLevel) : '');
    }
  };

  const onTypeChange = async (raw: string) => {
    if (!village) return;
    const next: VillageType | null =
      raw === '' ? null : (raw as VillageType);
    setSavingType(true);
    try {
      await patchClassification(village.tsLevel, next);
    } finally {
      setSavingType(false);
    }
  };

  const commitTs = async () => {
    if (!village) return;
    const raw = tsDraft.trim();
    let next: number | null;
    if (raw === '') {
      next = null;
    } else {
      const parsed = Number.parseInt(raw, 10);
      if (Number.isNaN(parsed) || parsed < 0 || parsed > 20) {
        // Reset to last-known-good and bail.
        setTsDraft(village.tsLevel != null ? String(village.tsLevel) : '');
        return;
      }
      next = parsed;
    }
    if (next === (village.tsLevel ?? null)) return; // no-op
    setSavingTs(true);
    try {
      await patchClassification(next, village.villageType);
    } finally {
      setSavingTs(false);
    }
  };

  return (
    <tr>
      <td>
        {village ? (
          <Link to={`/villages/${village.id}`} className="link">
            {village.name}
          </Link>
        ) : (
          <span className="muted">#{snapshot.villageId}</span>
        )}
        {village && (
          <div className="muted" style={{ fontSize: '0.75em' }}>
            ({village.coordinateX}|{village.coordinateY})
          </div>
        )}
      </td>
      <td>
        {village ? (
          <select
            value={village.villageType ?? ''}
            disabled={savingType}
            onChange={(e) => void onTypeChange(e.target.value)}
            style={{ fontSize: '0.85em', padding: '0.1rem 0.3rem' }}
            title="User-applied role tag. Drives ordering / filtering only."
          >
            <option value="">—</option>
            {VILLAGE_TYPES.map((vt) => (
              <option key={vt} value={vt}>
                {VILLAGE_TYPE_LABELS[vt]}
              </option>
            ))}
          </select>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>
        {village ? (
          <input
            type="number"
            min={0}
            max={20}
            value={tsDraft}
            disabled={savingTs}
            onChange={(e) => setTsDraft(e.target.value)}
            onBlur={() => void commitTs()}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur();
              if (e.key === 'Escape') {
                setTsDraft(village.tsLevel != null ? String(village.tsLevel) : '');
                (e.target as HTMLInputElement).blur();
              }
            }}
            placeholder="—"
            style={{ width: '3.5rem', fontSize: '0.85em', padding: '0.1rem 0.3rem' }}
            title="Tournament Square level 0..20. Blank = unknown. Enter to save, Esc to cancel."
          />
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>{cell(snapshot.t1)}</td>
      <td>{cell(snapshot.t2)}</td>
      <td>{cell(snapshot.t3)}</td>
      <td>{cell(snapshot.t4)}</td>
      <td>{cell(snapshot.t5)}</td>
      <td>{cell(snapshot.t6)}</td>
      <td>{cell(snapshot.t7)}</td>
      <td>{cell(snapshot.t8)}</td>
      <td>{cell(snapshot.t9)}</td>
      <td>{cell(snapshot.t10)}</td>
      <td>{cell(snapshot.hero)}</td>
      <td className="muted" style={{ fontSize: '0.8em' }}>
        {new Date(snapshot.updatedAt).toLocaleString()}
      </td>
    </tr>
  );
}

// ===========================================================================
//                             Summary panel
// ===========================================================================

/**
 * Coarse role buckets we surface in the summary. Skips chief + settler — those
 * appear in the per-column totals but rarely belong in an army-strength
 * rollup, and folding them in would distort the inf-off / cav-off numbers
 * the user actually cares about for war planning.
 */
const ROLE_DISPLAY_ORDER: ReadonlyArray<{ role: TroopRole; label: string }> = [
  { role: 'inf_off', label: 'Inf off' },
  { role: 'cav_off', label: 'Cav off' },
  { role: 'inf_def', label: 'Inf def' },
  { role: 'cav_def', label: 'Cav def' },
  { role: 'scout',   label: 'Scout' },
  { role: 'ram',     label: 'Ram' },
  { role: 'cat',     label: 'Cat' },
  { role: 'chief',   label: 'Chief' },
  { role: 'settler', label: 'Settler' },
];

interface VillageTypeRollup {
  villageCount: number;
  perColumn: number[];   // length 10
  hero: number;
  perRole: Record<TroopRole, number>;
}

interface Aggregate {
  perColumn: number[];   // grand total per t1..t10
  hero: number;
  perRole: Record<TroopRole, number>;
  /** Keyed by VillageType or the literal '__unclassified' for nulls. */
  perVillageType: Map<VillageType | '__unclassified', VillageTypeRollup>;
}

function emptyRoleBucket(): Record<TroopRole, number> {
  return {
    inf_def: 0, inf_off: 0, cav_off: 0, cav_def: 0,
    scout: 0, ram: 0, cat: 0, chief: 0, settler: 0,
  };
}

function emptyRollup(): VillageTypeRollup {
  return {
    villageCount: 0,
    perColumn: new Array(10).fill(0),
    hero: 0,
    perRole: emptyRoleBucket(),
  };
}

/**
 * Walk every snapshot once, building grand totals + per-role + per-village-type
 * rollups in a single pass. Tribe may be null — we still get column / hero
 * totals; the role-bucketed numbers just stay zero (no way to know what t3
 * means without the tribe).
 */
function aggregate(
  counts: VillageTroopCount[],
  villagesById: Map<number, Village>,
  tribe: Tribe | null,
): Aggregate {
  const perColumn = new Array(10).fill(0) as number[];
  let hero = 0;
  const perRole = emptyRoleBucket();
  const perVillageType = new Map<VillageType | '__unclassified', VillageTypeRollup>();

  for (const c of counts) {
    const v = villagesById.get(c.villageId);
    const key: VillageType | '__unclassified' = v?.villageType ?? '__unclassified';
    let bucket = perVillageType.get(key);
    if (!bucket) {
      bucket = emptyRollup();
      perVillageType.set(key, bucket);
    }
    bucket.villageCount += 1;

    const cols = [
      c.t1, c.t2, c.t3, c.t4, c.t5,
      c.t6, c.t7, c.t8, c.t9, c.t10,
    ];
    for (let i = 0; i < 10; i += 1) {
      const n = cols[i] ?? 0;
      perColumn[i] += n;
      bucket.perColumn[i] += n;
      if (tribe && n > 0) {
        const role = tribe.troops[i]?.role;
        if (role) {
          perRole[role] += n;
          bucket.perRole[role] += n;
        }
      }
    }
    hero += c.hero ?? 0;
    bucket.hero += c.hero ?? 0;
  }

  return { perColumn, hero, perRole, perVillageType };
}

function formatNumber(n: number): string {
  return n.toLocaleString();
}

function TroopsSummaryPanel({
  counts,
  villages,
  tribe,
  columnLabels,
}: {
  counts: VillageTroopCount[];
  villages: Village[];
  tribe: Tribe | null;
  columnLabels: string[];
}) {
  const villagesById = useMemo(() => {
    const m = new Map<number, Village>();
    for (const v of villages) m.set(v.id, v);
    return m;
  }, [villages]);

  const agg = useMemo(
    () => aggregate(counts, villagesById, tribe),
    [counts, villagesById, tribe],
  );

  const hasAnyRoleData = ROLE_DISPLAY_ORDER.some(
    ({ role }) => agg.perRole[role] > 0,
  );

  // Order rollup rows by the canonical VillageType order, then unclassified
  // last. Filters out type buckets the user hasn't actually used so the
  // table doesn't show six empty rows on a fresh account.
  const rollupRows = useMemo(() => {
    const ordered: Array<{
      key: VillageType | '__unclassified';
      label: string;
      rollup: VillageTypeRollup;
    }> = [];
    for (const vt of VILLAGE_TYPES) {
      const rollup = agg.perVillageType.get(vt);
      if (rollup) ordered.push({ key: vt, label: VILLAGE_TYPE_LABELS[vt], rollup });
    }
    const unclassified = agg.perVillageType.get('__unclassified');
    if (unclassified) {
      ordered.push({
        key: '__unclassified',
        label: 'Unclassified',
        rollup: unclassified,
      });
    }
    return ordered;
  }, [agg.perVillageType]);

  return (
    <Card
      title="Summary"
      hint={`${counts.length} village${counts.length === 1 ? '' : 's'} aggregated`}
    >
      <div className="stack" style={{ gap: '1rem' }}>
        {/* Grand totals row — one number per troop column + hero */}
        <div>
          <h3 style={{ margin: '0 0 0.4rem 0', fontSize: '0.9rem' }}>
            Grand totals
          </h3>
          <div style={{ overflowX: 'auto' }}>
            <table className="table" style={{ marginBottom: 0 }}>
              <thead>
                <tr>
                  {columnLabels.map((label) => (
                    <th key={label} style={{ minWidth: '5rem' }}>
                      {label}
                    </th>
                  ))}
                  <th style={{ minWidth: '4rem' }}>Hero</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  {agg.perColumn.map((n, i) => (
                    <td
                      key={i}
                      style={{
                        fontFamily: 'monospace',
                        fontWeight: n > 0 ? 600 : 400,
                      }}
                    >
                      {n === 0 ? <span className="muted">—</span> : formatNumber(n)}
                    </td>
                  ))}
                  <td style={{ fontFamily: 'monospace', fontWeight: agg.hero > 0 ? 600 : 400 }}>
                    {agg.hero === 0 ? <span className="muted">—</span> : formatNumber(agg.hero)}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        {/* Role breakdown — needs tribe; skip the block if we don't know it */}
        {hasAnyRoleData && (
          <div>
            <h3 style={{ margin: '0 0 0.4rem 0', fontSize: '0.9rem' }}>
              By role
            </h3>
            <div
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: '0.5rem 1rem',
                fontSize: '0.9em',
              }}
            >
              {ROLE_DISPLAY_ORDER.filter(({ role }) => agg.perRole[role] > 0).map(
                ({ role, label }) => (
                  <span key={role}>
                    <span className="muted">{label}:</span>{' '}
                    <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>
                      {formatNumber(agg.perRole[role])}
                    </span>
                  </span>
                ),
              )}
            </div>
          </div>
        )}

        {/* Per-village-type rollup */}
        {rollupRows.length > 0 && (
          <div>
            <h3 style={{ margin: '0 0 0.4rem 0', fontSize: '0.9rem' }}>
              By village type
            </h3>
            <div style={{ overflowX: 'auto' }}>
              <table className="table" style={{ marginBottom: 0 }}>
                <thead>
                  <tr>
                    <th style={{ minWidth: '8rem' }}>Type</th>
                    <th style={{ minWidth: '3rem' }}>Villages</th>
                    <th>Inf off</th>
                    <th>Cav off</th>
                    <th>Inf def</th>
                    <th>Cav def</th>
                    <th>Siege</th>
                    <th>Scout</th>
                    <th>Hero</th>
                  </tr>
                </thead>
                <tbody>
                  {rollupRows.map(({ key, label, rollup }) => {
                    const siege = rollup.perRole.ram + rollup.perRole.cat;
                    return (
                      <tr key={key}>
                        <td>{label}</td>
                        <td style={{ fontFamily: 'monospace' }}>
                          {rollup.villageCount}
                        </td>
                        <SumCell n={rollup.perRole.inf_off} />
                        <SumCell n={rollup.perRole.cav_off} />
                        <SumCell n={rollup.perRole.inf_def} />
                        <SumCell n={rollup.perRole.cav_def} />
                        <SumCell n={siege} />
                        <SumCell n={rollup.perRole.scout} />
                        <SumCell n={rollup.hero} />
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

function SumCell({ n }: { n: number }) {
  return (
    <td style={{ fontFamily: 'monospace', fontWeight: n > 0 ? 600 : 400 }}>
      {n === 0 ? <span className="muted">—</span> : formatNumber(n)}
    </td>
  );
}
