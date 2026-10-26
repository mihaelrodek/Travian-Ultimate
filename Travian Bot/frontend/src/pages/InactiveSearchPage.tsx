import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import ServerPickerCard from '../features/servers/ServerPickerCard';
import { useCurrentServer } from '../hooks/useCurrentServer';
import { intelApi } from '../api/intel';
import { serversApi } from '../api/servers';
import { ApiException } from '../types/api';
import {
  TRIBE_LABELS,
  type Alliance,
  type InactivePlayer,
  type InactivePlayerPage,
  type InactiveSearchFilters,
  type InactiveSortBy,
  type SortDir,
} from '../types/intel';
import type { PivotVillage } from '../types/server';
import InactivePlayerDetailModal from '../features/intel/InactivePlayerDetailModal';

/**
 * Inactive-player search page (server-scoped variant).
 *
 * Filter form supports 13 parameters mirrored to backend
 * {@code InactiveSearchService.InactiveSearchFilters} — keeps parity with
 * inactivesearch.it / travcotools coverage. Click any row to open the player
 * detail modal showing every tracked village + meta.
 *
 * The page is scoped to a Travian *server* (world), not to a specific
 * account: the map.sql dataset is shared between every account on the same
 * world. The pivot-village picker offers every village across every local
 * account that uses the picked server so the distance filter still works
 * cross-account.
 */

const PAGE_SIZE = 50;
const DEFAULT_MIN_IDLE_DAYS = 14;
const DEFAULT_MAX_POPULATION = 500;
const DEFAULT_MAX_DISTANCE = 30;
const DEFAULT_MAX_POP_INCREASE = 5;
const DEFAULT_LAST_SEEN_DAYS = 30;

const TRIBE_OPTIONS: Array<{ id: number; label: string }> = [
  { id: 1, label: 'Romans' },
  { id: 2, label: 'Teutons' },
  { id: 3, label: 'Gauls' },
  { id: 5, label: 'Natars' },
  { id: 6, label: 'Egyptians' },
  { id: 7, label: 'Huns' },
];

const SORT_OPTIONS: Array<{ value: InactiveSortBy; label: string }> = [
  { value: 'distance',     label: 'Distance' },
  { value: 'population',   label: 'Population' },
  { value: 'idleDays',     label: 'Days idle' },
  { value: 'villageCount', label: 'Village count' },
  { value: 'lastSeen',     label: 'Last seen' },
];

type NumberOrEmpty = number | '';

export default function InactiveSearchPage() {
  const { server, serverId, setCurrentServer } = useCurrentServer();
  const [searchParams, setSearchParams] = useSearchParams();

  // --- Filter inputs (un-applied) ---
  const [minIdleDays, setMinIdleDays] = useState<NumberOrEmpty>(DEFAULT_MIN_IDLE_DAYS);
  const [maxPopulation, setMaxPopulation] = useState<NumberOrEmpty>(DEFAULT_MAX_POPULATION);
  const [minPopulation, setMinPopulation] = useState<NumberOrEmpty>('');
  const [maxPopIncrease, setMaxPopIncrease] = useState<NumberOrEmpty>(DEFAULT_MAX_POP_INCREASE);
  const [minVillageCount, setMinVillageCount] = useState<NumberOrEmpty>('');
  const [maxVillageCount, setMaxVillageCount] = useState<NumberOrEmpty>('');
  const [xMin, setXMin] = useState<NumberOrEmpty>('');
  const [xMax, setXMax] = useState<NumberOrEmpty>('');
  const [yMin, setYMin] = useState<NumberOrEmpty>('');
  const [yMax, setYMax] = useState<NumberOrEmpty>('');
  const [tribeIds, setTribeIds] = useState<number[]>([]);
  const [nearVillageId, setNearVillageId] = useState<NumberOrEmpty>('');
  const [maxDistance, setMaxDistance] = useState<NumberOrEmpty>(DEFAULT_MAX_DISTANCE);
  const [allianceExclude, setAllianceExclude] = useState<string>('');
  const [allianceInclude, setAllianceInclude] = useState<string>('');
  /**
   * Alliance chosen via the autocomplete (or pre-filled from ?alliance=N).
   * When set, drives the {@code allianceTravianId} query param so the
   * backend can scope results to one alliance's roster.
   */
  const [selectedAlliance, setSelectedAlliance] = useState<{ travianAid: number; name: string } | null>(null);
  const [lastSeenWithinDays, setLastSeenWithinDays] = useState<NumberOrEmpty>(DEFAULT_LAST_SEEN_DAYS);
  const [inactiveOnly, setInactiveOnly] = useState<boolean>(true);
  const [sortBy, setSortBy] = useState<InactiveSortBy | ''>('');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [showAdvanced, setShowAdvanced] = useState<boolean>(false);

  // Results / fetch state
  const [page, setPage] = useState<number>(0);
  const [data, setData] = useState<InactivePlayerPage | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Pivot-village picker source — villages on every account that uses this server.
  const [serverVillages, setServerVillages] = useState<PivotVillage[]>([]);

  // Action button state
  const [syncBusy, setSyncBusy] = useState<boolean>(false);
  const [syncMsg, setSyncMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [rowMsg, setRowMsg] = useState<{ uid: number; kind: 'ok' | 'err'; text: string } | null>(null);
  const [rowBusy, setRowBusy] = useState<Record<number, boolean>>({});

  // Detail modal
  const [openUid, setOpenUid] = useState<number | null>(null);

  /**
   * When the AlliancesPage drills into us via {@code /intel?alliance=N} we
   * need to (a) pre-fill the alliance filter and (b) auto-apply so the user
   * lands on a populated table. {@code pendingAutoApply} is the handshake
   * flag between this URL-reading effect and the load effect below.
   */
  const [pendingAutoApply, setPendingAutoApply] = useState<boolean>(false);

  // Reset pivot/filters whenever the server changes — values from the old
  // server's village list would be meaningless against the new dataset.
  useEffect(() => {
    setNearVillageId('');
    setPage(0);
    setData(null);
    setRowMsg(null);
    setSyncMsg(null);
    // Don't clobber selectedAlliance here — server change may legitimately
    // invalidate it, but the URL effect below handles that case explicitly.
  }, [serverId]);

  // Read ?alliance=N from the URL on mount (or when serverId becomes
  // available). On hit, fetch the alliance name so the chip can display it
  // and trigger an auto-apply so the user sees results immediately.
  useEffect(() => {
    if (serverId == null) return;
    const aidStr = searchParams.get('alliance');
    if (!aidStr) return;
    const aid = Number(aidStr);
    if (!Number.isFinite(aid) || aid <= 0) return;
    // Avoid clobbering a chip the user has already picked from the autocomplete.
    if (selectedAlliance && selectedAlliance.travianAid === aid) return;
    let cancelled = false;
    intelApi
      .getAlliance(serverId, aid)
      .then((a) => {
        if (cancelled) return;
        setSelectedAlliance({ travianAid: a.travianAid, name: a.name });
        setPage(0);
        setPendingAutoApply(true);
      })
      .catch(() => {
        if (cancelled) return;
        // Couldn't resolve the name — still set the filter so the query runs,
        // but use a placeholder label.
        setSelectedAlliance({ travianAid: aid, name: `aid ${aid}` });
        setPage(0);
        setPendingAutoApply(true);
      });
    return () => {
      cancelled = true;
    };
    // We only care about the initial value of the query param for this server.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, searchParams]);

  useEffect(() => {
    if (serverId == null) {
      setServerVillages([]);
      return;
    }
    let cancelled = false;
    serversApi
      .listVillages(serverId)
      .then((vs) => { if (!cancelled) setServerVillages(vs); })
      .catch(() => { if (!cancelled) setServerVillages([]); });
    return () => { cancelled = true; };
  }, [serverId]);

  const buildFilters = useCallback((): InactiveSearchFilters => {
    const num = (v: NumberOrEmpty): number | undefined => v === '' ? undefined : Number(v);
    return {
      minIdleDays: num(minIdleDays),
      maxPopulation: num(maxPopulation),
      minPopulation: num(minPopulation),
      maxPopulationIncrease: num(maxPopIncrease),
      minVillageCount: num(minVillageCount),
      maxVillageCount: num(maxVillageCount),
      xMin: num(xMin),
      xMax: num(xMax),
      yMin: num(yMin),
      yMax: num(yMax),
      tribeIds: tribeIds.length > 0 ? tribeIds : undefined,
      nearVillageId: num(nearVillageId),
      maxDistance: nearVillageId === '' ? undefined : num(maxDistance),
      allianceExclude: allianceExclude.trim() || undefined,
      allianceInclude: allianceInclude.trim() || undefined,
      allianceTravianId: selectedAlliance?.travianAid,
      lastSeenWithinDays: num(lastSeenWithinDays),
      inactiveOnly: inactiveOnly ? true : undefined,
      sortBy: sortBy || undefined,
      sortDir: sortBy ? sortDir : undefined,
      page,
      size: PAGE_SIZE,
    };
  }, [
    minIdleDays, maxPopulation, minPopulation, maxPopIncrease,
    minVillageCount, maxVillageCount, xMin, xMax, yMin, yMax,
    tribeIds, nearVillageId, maxDistance,
    allianceExclude, allianceInclude, selectedAlliance, lastSeenWithinDays,
    inactiveOnly, sortBy, sortDir, page,
  ]);

  const load = useCallback(async () => {
    if (serverId == null) {
      setData(null);
      return;
    }
    setLoading(true);
    try {
      const resp = await intelApi.searchInactives(serverId, buildFilters());
      setData(resp);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load inactives');
    } finally {
      setLoading(false);
    }
  }, [serverId, buildFilters]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, page]);

  // Fire once after the URL-prefill effect resolves the alliance — runs the
  // search so the user lands on a populated table without having to click
  // Apply themselves.
  useEffect(() => {
    if (!pendingAutoApply) return;
    setPendingAutoApply(false);
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAutoApply]);

  const apply = () => {
    setPage(0);
    void load();
  };

  const resetFilters = () => {
    setMinIdleDays(DEFAULT_MIN_IDLE_DAYS);
    setMaxPopulation(DEFAULT_MAX_POPULATION);
    setMinPopulation('');
    setMaxPopIncrease(DEFAULT_MAX_POP_INCREASE);
    setMinVillageCount('');
    setMaxVillageCount('');
    setXMin(''); setXMax(''); setYMin(''); setYMax('');
    setTribeIds([]);
    setNearVillageId('');
    setMaxDistance(DEFAULT_MAX_DISTANCE);
    setAllianceExclude('');
    setAllianceInclude('');
    setSelectedAlliance(null);
    // Drop the ?alliance=N from the URL too so a future reset isn't
    // immediately undone by the URL-reading effect.
    if (searchParams.has('alliance')) {
      const next = new URLSearchParams(searchParams);
      next.delete('alliance');
      setSearchParams(next, { replace: true });
    }
    setLastSeenWithinDays(DEFAULT_LAST_SEEN_DAYS);
    setInactiveOnly(true);
    setSortBy('');
    setSortDir('asc');
    setPage(0);
  };

  const sync = async () => {
    if (serverId == null) return;
    setSyncBusy(true);
    setSyncMsg(null);
    try {
      const resp = await intelApi.sync(serverId);
      setSyncMsg({ kind: 'ok', text: `Sync done — ${resp.playersSynced} player(s) refreshed.` });
      await load();
    } catch (err) {
      setSyncMsg({ kind: 'err', text: err instanceof Error ? err.message : 'Sync failed' });
    } finally {
      setSyncBusy(false);
    }
  };

  const pivotVillage = useMemo(
    () => serverVillages.find((x) => x.id === Number(nearVillageId)) ?? null,
    [nearVillageId, serverVillages],
  );

  const addToFarmlist = async (row: InactivePlayer) => {
    // Farmlists are account-scoped, so we need an account context. Resolve
    // from the pivot village — that's the only account the user has signalled
    // intent for on this page.
    const accountId = pivotVillage?.accountId ?? null;
    if (accountId == null) {
      setRowMsg({
        uid: row.travianUid,
        kind: 'err',
        text: 'Pick a pivot village from one of your accounts to enable farmlist add.',
      });
      return;
    }
    setRowBusy((s) => ({ ...s, [row.travianUid]: true }));
    setRowMsg(null);
    try {
      await intelApi.quickAddToFarmlist(accountId, row.travianUid);
      setRowMsg({ uid: row.travianUid, kind: 'ok', text: `Added "${row.name}" to the default farmlist.` });
    } catch (err) {
      const text =
        err instanceof ApiException && err.status === 404
          ? 'Farmlist quick-add isn\'t wired up yet — coming soon.'
          : err instanceof Error
            ? err.message
            : 'Add to farmlist failed';
      setRowMsg({ uid: row.travianUid, kind: 'err', text });
    } finally {
      setRowBusy((s) => {
        const { [row.travianUid]: _, ...rest } = s;
        return rest;
      });
    }
  };

  const toggleTribe = (id: number) => {
    setTribeIds((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));
  };

  const items = data?.items ?? [];
  const totalPages = data?.totalPages ?? 0;
  const total = data?.total ?? 0;

  const pivotLabel = pivotVillage
    ? `${pivotVillage.accountName} → ${pivotVillage.villageName} (${pivotVillage.x}|${pivotVillage.y})`
    : null;
  const pivotCoords = pivotVillage
    ? { x: pivotVillage.x, y: pivotVillage.y }
    : null;

  const canAddToFarmlist = pivotVillage != null;

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Neaktivni"
        subtitle="Niska populacija + slab rast = dobre mete za farmanje."
      />
      </div>

      <ServerPickerCard serverId={serverId} onChange={setCurrentServer} />

      {serverId == null && (
        <Card>
          <p className="muted">
            Pick a Travian server above to start searching.
          </p>
        </Card>
      )}

      {serverId != null && (
        <>
          <Card title="Filters" hint="Hit Apply after editing — filters don't auto-submit.">
            {/* Basic row */}
            <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(11rem, 1fr))', gap: '0.75rem' }}>
              <NumField label="Min idle days" v={minIdleDays} set={setMinIdleDays} min={0} max={365} />
              <NumField label="Max population" v={maxPopulation} set={setMaxPopulation} min={0} max={50000} />
              <NumField label="Max 7d growth"  v={maxPopIncrease} set={setMaxPopIncrease} min={0} max={5000}
                        hint="Exclude players whose pop grew by more than this in last 7d" />
              <NumField label="Last seen ≤ days" v={lastSeenWithinDays} set={setLastSeenWithinDays} min={0} max={365} />

              <label className="form__field">
                <span>Near my village</span>
                <select
                  value={nearVillageId === '' ? '' : String(nearVillageId)}
                  onChange={(e) => setNearVillageId(e.target.value === '' ? '' : Number(e.target.value))}
                  disabled={serverVillages.length === 0}
                >
                  <option value="">
                    {serverVillages.length === 0 ? '— no villages on this server —' : '— none —'}
                  </option>
                  {serverVillages.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.accountName} → {v.villageName} ({v.x}|{v.y})
                    </option>
                  ))}
                </select>
              </label>

              <NumField label="Max distance (fields)" v={maxDistance} set={setMaxDistance} min={1} max={400}
                        disabled={nearVillageId === ''}
                        disabledTitle="Pick a pivot village first" />

              <label className="form__field" style={{ alignSelf: 'end' }}>
                <span>&nbsp;</span>
                <label className="form__check">
                  <input type="checkbox" checked={inactiveOnly} onChange={(e) => setInactiveOnly(e.target.checked)} />
                  Inactive only
                </label>
              </label>
            </div>

            {/* Alliance autocomplete row — typing fires a debounced search
                against /intel/alliances; picking a suggestion stores the
                travianAid that's sent as allianceTravianId in buildFilters. */}
            <div style={{ marginTop: '0.75rem' }}>
              <AllianceAutocomplete
                serverId={serverId}
                selected={selectedAlliance}
                onSelect={(a) => {
                  setSelectedAlliance(a);
                  setPage(0);
                }}
                onClear={() => {
                  setSelectedAlliance(null);
                  // Strip ?alliance from URL so the chip stays cleared on
                  // refresh — same logic as resetFilters.
                  if (searchParams.has('alliance')) {
                    const next = new URLSearchParams(searchParams);
                    next.delete('alliance');
                    setSearchParams(next, { replace: true });
                  }
                  setPage(0);
                }}
              />
            </div>

            {/* Sort row */}
            <div className="toolbar" style={{ gap: '0.75rem', marginTop: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <label className="form__field" style={{ minWidth: '10rem' }}>
                <span>Sort by</span>
                <select value={sortBy} onChange={(e) => setSortBy(e.target.value as InactiveSortBy | '')}>
                  <option value="">— default —</option>
                  {SORT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </label>
              <label className="form__field" style={{ minWidth: '7rem' }}>
                <span>Direction</span>
                <select value={sortDir} onChange={(e) => setSortDir(e.target.value as SortDir)} disabled={!sortBy}>
                  <option value="asc">Ascending</option>
                  <option value="desc">Descending</option>
                </select>
              </label>
              <button type="button" className="btn btn--ghost" onClick={() => setShowAdvanced((s) => !s)}>
                {showAdvanced ? 'Hide advanced ▴' : 'Show advanced ▾'}
              </button>
            </div>

            {/* Advanced section */}
            {showAdvanced && (
              <div style={{ marginTop: '0.75rem', padding: '0.75rem', background: 'var(--color-surface-2, rgba(0,0,0,0.03))', borderRadius: 4 }}>
                <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(11rem, 1fr))', gap: '0.75rem' }}>
                  <NumField label="Min population" v={minPopulation} set={setMinPopulation} min={0} max={50000} />
                  <NumField label="Min village count" v={minVillageCount} set={setMinVillageCount} min={0} max={1000} />
                  <NumField label="Max village count" v={maxVillageCount} set={setMaxVillageCount} min={0} max={1000} />
                </div>

                <div style={{ marginTop: '0.5rem' }}>
                  <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                    Coordinate bounding box (player passes if any village inside)
                  </span>
                  <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '0.5rem' }}>
                    <NumField label="x min" v={xMin} set={setXMin} />
                    <NumField label="x max" v={xMax} set={setXMax} />
                    <NumField label="y min" v={yMin} set={setYMin} />
                    <NumField label="y max" v={yMax} set={setYMax} />
                  </div>
                </div>

                <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(14rem, 1fr))', gap: '0.75rem', marginTop: '0.5rem' }}>
                  <label className="form__field">
                    <span>Alliance excludes</span>
                    <input type="text" value={allianceExclude} onChange={(e) => setAllianceExclude(e.target.value)} placeholder="case-insensitive substring" />
                  </label>
                  <label className="form__field">
                    <span>Alliance includes</span>
                    <input type="text" value={allianceInclude} onChange={(e) => setAllianceInclude(e.target.value)} placeholder="case-insensitive substring" />
                  </label>
                </div>

                <div style={{ marginTop: '0.5rem' }}>
                  <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                    Tribes (empty = all)
                  </span>
                  <div className="toolbar" style={{ flexWrap: 'wrap', gap: '0.5rem' }}>
                    {TRIBE_OPTIONS.map((t) => (
                      <label key={t.id} className="form__check">
                        <input type="checkbox" checked={tribeIds.includes(t.id)} onChange={() => toggleTribe(t.id)} />
                        {t.label}
                      </label>
                    ))}
                  </div>
                </div>
              </div>
            )}

            <div className="toolbar" style={{ gap: '0.5rem', marginTop: '0.75rem', flexWrap: 'wrap' }}>
              <button type="button" className="btn btn--primary" onClick={apply} disabled={loading}>
                {loading ? 'Loading…' : 'Apply filters'}
              </button>
              <button type="button" className="btn btn--ghost" onClick={resetFilters}>Reset</button>
              <button type="button" className="btn btn--ghost" onClick={sync} disabled={syncBusy}>
                {syncBusy ? 'Syncing…' : 'Sync now'}
              </button>
              {syncMsg && (
                <span
                  className={syncMsg.kind === 'ok' ? 'form__msg form__msg--success' : 'form__msg form__msg--error'}
                  style={{ flex: 1 }}
                >
                  {syncMsg.text}
                </span>
              )}
            </div>
          </Card>

          <Card
            title={`Results (${total})`}
            hint={
              pivotLabel
                ? `Distance computed from ${pivotLabel}. Click a row for full player view.`
                : 'Pick a pivot village above to sort by distance. Click a row for full player view.'
            }
          >
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!error && loading && <p className="muted">Loading…</p>}
            {!error && !loading && items.length === 0 && (
              <div>
                <p className="muted">
                  No matches. Try widening the filters or hitting <em>Sync now</em> to pull the latest map.
                </p>
                {(inactiveOnly || (minIdleDays !== '' && Number(minIdleDays) > 0)) && (
                  <div className="form__msg" style={{ marginTop: '0.5rem', padding: '0.75rem', background: 'rgba(255,200,80,0.15)', borderRadius: 4 }}>
                    <strong>Tip:</strong> on a fresh sync, no player is flagged "inactive" yet
                    — we need ≥ 7 days of population history. Your defaults
                    {inactiveOnly && <> include <code>Inactive only</code></>}
                    {inactiveOnly && minIdleDays !== '' && Number(minIdleDays) > 0 && <> and </>}
                    {minIdleDays !== '' && Number(minIdleDays) > 0 && <> require <code>Min idle days ≥ {minIdleDays}</code></>}
                    , so the result set is empty. Click below to loosen them.
                    <div style={{ marginTop: '0.5rem' }}>
                      <button
                        type="button"
                        className="btn btn--primary"
                        onClick={() => {
                          setInactiveOnly(false);
                          setMinIdleDays('');
                          setPage(0);
                          void load();
                        }}
                      >
                        Show all players on this server
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
            {!error && items.length > 0 && (
              <>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Alliance</th>
                      <th>Tribe</th>
                      <th>Pop</th>
                      <th>Δ 7d</th>
                      <th>Villages</th>
                      <th>Days idle</th>
                      <th>Distance</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((row) => {
                      const busy = rowBusy[row.travianUid] === true;
                      const msg = rowMsg && rowMsg.uid === row.travianUid ? rowMsg : null;
                      const deltaText =
                        row.populationDelta7d == null
                          ? '—'
                          : row.populationDelta7d > 0
                            ? `↑ ${row.populationDelta7d}`
                            : row.populationDelta7d < 0
                              ? `↓ ${Math.abs(row.populationDelta7d)}`
                              : '· 0';
                      return (
                        <tr
                          key={row.id}
                          onClick={() => setOpenUid(row.travianUid)}
                          style={{ cursor: 'pointer' }}
                          title="Click for full player view"
                        >
                          <td><strong>{row.name}</strong></td>
                          <td>{row.allianceName ?? <span className="muted">—</span>}</td>
                          <td>{TRIBE_LABELS[row.tribeId] ?? row.tribeId}</td>
                          <td>{row.currentPopulation}</td>
                          <td className={row.populationDelta7d != null && row.populationDelta7d <= 0 ? 'muted' : ''}>
                            {deltaText}
                          </td>
                          <td>{row.villageCount}</td>
                          <td>{row.daysSincePopChange ?? <span className="muted">—</span>}</td>
                          <td>
                            {row.nearestVillageDistance != null
                              ? row.nearestVillageDistance.toFixed(1)
                              : <span className="muted">—</span>}
                          </td>
                          <td>
                            {row.isInactive
                              ? <span className="pill pill--success">Inactive</span>
                              : <span className="pill">Active</span>}
                          </td>
                          <td style={{ minWidth: '12rem' }} onClick={(e) => e.stopPropagation()}>
                            <button
                              type="button"
                              className="btn btn--ghost"
                              onClick={() => void addToFarmlist(row)}
                              disabled={busy || !canAddToFarmlist}
                              title={
                                canAddToFarmlist
                                  ? `Add this player's villages to ${pivotVillage?.accountName}'s default farmlist`
                                  : 'Pick a pivot village from one of your accounts to enable farmlist add'
                              }
                            >
                              {busy ? 'Adding…' : 'Add to farmlist'}
                            </button>
                            {msg && (
                              <div
                                className={msg.kind === 'ok' ? 'form__msg form__msg--success' : 'form__msg form__msg--error'}
                                style={{ fontSize: '0.75rem', marginTop: '0.25rem' }}
                              >
                                {msg.text}
                              </div>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>

                {totalPages > 1 && (
                  <div className="toolbar" style={{ gap: '0.5rem', marginTop: '0.75rem', justifyContent: 'flex-end' }}>
                    <button
                      type="button"
                      className="btn btn--ghost"
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                      disabled={page === 0 || loading}
                    >‹ Prev</button>
                    <span className="muted" style={{ alignSelf: 'center' }}>
                      Page {page + 1} of {totalPages}
                    </span>
                    <button
                      type="button"
                      className="btn btn--ghost"
                      onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                      disabled={page >= totalPages - 1 || loading}
                    >Next ›</button>
                  </div>
                )}
              </>
            )}
          </Card>

          {openUid != null && (
            <InactivePlayerDetailModal
              serverId={serverId}
              uid={openUid}
              serverUrl={server?.url ?? null}
              pivot={pivotCoords}
              onClose={() => setOpenUid(null)}
            />
          )}
        </>
      )}
    </div>
  );
}

/**
 * Self-contained alliance picker with debounced search-as-you-type.
 *
 * Behaviour:
 *  - On focus (or as soon as the user types ≥ 2 chars) we debounce 250 ms
 *    and fire {@code intelApi.listAlliances({ search, size: 20 })}. This is
 *    intentionally self-debounced rather than using a shared hook — the
 *    project doesn't have one and pulling in a dep just for this is overkill.
 *  - The result list renders below the input. Clicking a row calls
 *    {@link Props.onSelect} with {@code { travianAid, name }} so the parent
 *    can persist the chip and include the filter in the next search.
 *  - Once an alliance is picked, the input is replaced by a removable chip;
 *    clicking ✕ on the chip fires {@link Props.onClear} so the parent can
 *    drop the filter (and clear any ?alliance=N from the URL).
 *
 * No "Apply" needed — picking the chip already changed `selectedAlliance`,
 * but the user still has to hit Apply on the form to re-run the inactive
 * search. That matches the rest of the form's "filters don't auto-submit"
 * contract.
 */
function AllianceAutocomplete({
  serverId,
  selected,
  onSelect,
  onClear,
}: {
  serverId: number;
  selected: { travianAid: number; name: string } | null;
  onSelect: (a: { travianAid: number; name: string }) => void;
  onClear: () => void;
}) {
  // TODO i18n — strings here are hardcoded English.
  const [query, setQuery] = useState<string>('');
  const [open, setOpen] = useState<boolean>(false);
  const [suggestions, setSuggestions] = useState<Alliance[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [err, setErr] = useState<string | null>(null);
  const debounceRef = useRef<number | null>(null);
  const reqIdRef = useRef<number>(0);

  // Debounced fetch — fires 250ms after the last keystroke, or immediately
  // on focus if the input already has ≥ 2 chars.
  const scheduleFetch = useCallback((text: string) => {
    if (debounceRef.current != null) {
      window.clearTimeout(debounceRef.current);
    }
    if (text.trim().length < 2) {
      setSuggestions([]);
      setLoading(false);
      return;
    }
    debounceRef.current = window.setTimeout(() => {
      const myReqId = ++reqIdRef.current;
      setLoading(true);
      setErr(null);
      intelApi
        .listAlliances(serverId, { search: text.trim(), size: 20 })
        .then((page) => {
          // Drop the response if a newer request has been kicked off in the
          // meantime — avoids flicker / stale results when typing fast.
          if (myReqId !== reqIdRef.current) return;
          setSuggestions(page.items);
        })
        .catch((e) => {
          if (myReqId !== reqIdRef.current) return;
          setErr(e instanceof Error ? e.message : 'Alliance search failed');
          setSuggestions([]);
        })
        .finally(() => {
          if (myReqId === reqIdRef.current) setLoading(false);
        });
    }, 250);
  }, [serverId]);

  // Reset on server change — alliance lists are per-server.
  useEffect(() => {
    setQuery('');
    setSuggestions([]);
    setErr(null);
    setOpen(false);
  }, [serverId]);

  useEffect(() => {
    return () => {
      if (debounceRef.current != null) window.clearTimeout(debounceRef.current);
    };
  }, []);

  if (selected) {
    // Chip mode — input collapsed to a removable badge.
    return (
      <label className="form__field" style={{ maxWidth: '32rem' }}>
        <span>Alliance</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <span
            className="pill"
            style={{ display: 'inline-flex', alignItems: 'center', gap: '0.4rem' }}
            title={`aid ${selected.travianAid}`}
          >
            {selected.name}
            <button
              type="button"
              onClick={onClear}
              aria-label="Clear alliance filter"
              style={{
                background: 'none', border: 'none', cursor: 'pointer',
                padding: 0, fontSize: '0.9rem', lineHeight: 1,
              }}
            >
              ✕
            </button>
          </span>
          <small className="muted" style={{ fontSize: '0.75rem' }}>
            Scoped to this alliance. Hit Apply to refresh.
          </small>
        </div>
      </label>
    );
  }

  return (
    <label
      className="form__field"
      style={{ position: 'relative', maxWidth: '32rem' }}
    >
      <span>Alliance</span>
      <input
        type="text"
        value={query}
        placeholder="type ≥ 2 chars to search alliances on this server"
        onFocus={() => {
          setOpen(true);
          if (query.trim().length >= 2 && suggestions.length === 0) {
            scheduleFetch(query);
          }
        }}
        onBlur={() => {
          // Delay close so the click handler on a suggestion fires first.
          window.setTimeout(() => setOpen(false), 150);
        }}
        onChange={(e) => {
          const v = e.target.value;
          setQuery(v);
          setOpen(true);
          scheduleFetch(v);
        }}
      />
      {open && (loading || suggestions.length > 0 || err) && (
        <div
          // Floating panel — anchored to the input via absolute positioning
          // inside the relative form__field wrapper.
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            right: 0,
            zIndex: 20,
            marginTop: 2,
            background: 'var(--color-surface, #fff)',
            border: '1px solid var(--border)',
            borderRadius: 4,
            boxShadow: '0 6px 20px rgba(0,0,0,0.15)',
            maxHeight: '18rem',
            overflowY: 'auto',
          }}
        >
          {loading && (
            <div className="muted" style={{ padding: '0.5rem 0.75rem', fontSize: '0.85rem' }}>
              Searching…
            </div>
          )}
          {err && !loading && (
            <div
              className="form__msg form__msg--error"
              style={{ padding: '0.5rem 0.75rem', fontSize: '0.85rem' }}
            >
              {err}
            </div>
          )}
          {!loading && !err && suggestions.length === 0 && query.trim().length >= 2 && (
            <div className="muted" style={{ padding: '0.5rem 0.75rem', fontSize: '0.85rem' }}>
              No matches.
            </div>
          )}
          {!loading && suggestions.map((a) => (
            <button
              key={a.id}
              type="button"
              // mousedown fires before blur — without it, the blur closes the
              // panel before the click can register on the button.
              onMouseDown={(e) => {
                e.preventDefault();
                onSelect({ travianAid: a.travianAid, name: a.name });
                setQuery('');
                setSuggestions([]);
                setOpen(false);
              }}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '0.5rem 0.75rem',
                background: 'none',
                border: 'none',
                borderBottom: '1px solid var(--border)',
                cursor: 'pointer',
                font: 'inherit',
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLButtonElement).style.background = 'var(--surface-muted)';
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLButtonElement).style.background = 'none';
              }}
            >
              <div><strong>{a.name}</strong></div>
              <div className="muted" style={{ fontSize: '0.75rem' }}>
                {a.memberCount} member{a.memberCount === 1 ? '' : 's'} ·{' '}
                {a.totalPopulation.toLocaleString()} pop · aid {a.travianAid}
              </div>
            </button>
          ))}
        </div>
      )}
    </label>
  );
}

/** Reusable numeric input bound to a `NumberOrEmpty` state. */
function NumField({
  label, v, set, min, max, disabled, disabledTitle, hint,
}: {
  label: string;
  v: NumberOrEmpty;
  set: (n: NumberOrEmpty) => void;
  min?: number;
  max?: number;
  disabled?: boolean;
  disabledTitle?: string;
  hint?: string;
}) {
  return (
    <label className="form__field">
      <span>{label}</span>
      <input
        type="number"
        min={min}
        max={max}
        value={v}
        onChange={(e) => set(e.target.value === '' ? '' : Number(e.target.value))}
        disabled={disabled}
        title={disabled ? disabledTitle : hint}
      />
      {hint && !disabled && <small className="muted" style={{ fontSize: '0.7rem' }}>{hint}</small>}
    </label>
  );
}
