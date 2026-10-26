import { useCallback, useEffect, useMemo, useState } from 'react';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import ServerPickerCard from '../features/servers/ServerPickerCard';
import { useCurrentServer } from '../hooks/useCurrentServer';
import { intelApi } from '../api/intel';
import { serversApi } from '../api/servers';
import {
  TRIBE_LABELS,
  type InactivePlayer,
  type InactivePlayerPage,
  type InactiveSearchFilters,
  type InactiveSortBy,
  type SortDir,
} from '../types/intel';
import type { PivotVillage } from '../types/server';
import InactivePlayerDetailModal from '../features/intel/InactivePlayerDetailModal';

/**
 * Full player browser for one Travian server.
 *
 * Shares the backend with {@link InactiveSearchPage} but defaults to NO
 * activity filtering — listing every player aggregated from {@code map.sql}.
 * Click any row to open the player detail modal with all villages.
 *
 * TODO i18n — strings hardcoded English.
 */

const PAGE_SIZE = 50;

const TRIBE_OPTIONS: Array<{ id: number; label: string }> = [
  { id: 1, label: 'Romans' },
  { id: 2, label: 'Teutons' },
  { id: 3, label: 'Gauls' },
  { id: 5, label: 'Natars' },
  { id: 6, label: 'Egyptians' },
  { id: 7, label: 'Huns' },
];

const SORT_OPTIONS: Array<{ value: InactiveSortBy; label: string }> = [
  { value: 'population',   label: 'Population' },
  { value: 'villageCount', label: 'Village count' },
  { value: 'distance',     label: 'Distance' },
  { value: 'idleDays',     label: 'Days idle' },
  { value: 'lastSeen',     label: 'Last seen' },
];

type NumberOrEmpty = number | '';

export default function PlayersPage() {
  const { server, serverId, setCurrentServer } = useCurrentServer();

  const [search, setSearch] = useState<string>('');
  const [minPopulation, setMinPopulation] = useState<NumberOrEmpty>('');
  const [maxPopulation, setMaxPopulation] = useState<NumberOrEmpty>('');
  const [minVillageCount, setMinVillageCount] = useState<NumberOrEmpty>('');
  const [maxVillageCount, setMaxVillageCount] = useState<NumberOrEmpty>('');
  const [tribeIds, setTribeIds] = useState<number[]>([]);
  const [nearVillageId, setNearVillageId] = useState<NumberOrEmpty>('');
  const [maxDistance, setMaxDistance] = useState<NumberOrEmpty>('');
  const [allianceExclude, setAllianceExclude] = useState<string>('');
  const [allianceInclude, setAllianceInclude] = useState<string>('');
  const [sortBy, setSortBy] = useState<InactiveSortBy>('population');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  const [page, setPage] = useState<number>(0);
  const [data, setData] = useState<InactivePlayerPage | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const [serverVillages, setServerVillages] = useState<PivotVillage[]>([]);
  const [openUid, setOpenUid] = useState<number | null>(null);

  // Reset paging + cached data when server changes
  useEffect(() => {
    setNearVillageId('');
    setPage(0);
    setData(null);
  }, [serverId]);

  // Pivot picker source for distance filter (optional)
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
    // Search-by-name uses the alliance-include text field as a generic
    // substring match on alliance OR player name. The backend doesn't have a
    // pure "playerName contains" filter yet, so for now we only filter by
    // alliance substring when present and post-filter player name client-side
    // (works fine for page-sized results).
    return {
      minPopulation: num(minPopulation),
      maxPopulation: num(maxPopulation),
      minVillageCount: num(minVillageCount),
      maxVillageCount: num(maxVillageCount),
      tribeIds: tribeIds.length > 0 ? tribeIds : undefined,
      nearVillageId: num(nearVillageId),
      maxDistance: nearVillageId === '' ? undefined : num(maxDistance),
      allianceExclude: allianceExclude.trim() || undefined,
      allianceInclude: allianceInclude.trim() || undefined,
      sortBy,
      sortDir,
      page,
      size: PAGE_SIZE,
    };
  }, [
    minPopulation, maxPopulation, minVillageCount, maxVillageCount,
    tribeIds, nearVillageId, maxDistance,
    allianceExclude, allianceInclude, sortBy, sortDir, page,
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
      setError(err instanceof Error ? err.message : 'Failed to load players');
    } finally {
      setLoading(false);
    }
  }, [serverId, buildFilters]);

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, page, sortBy, sortDir]);

  const apply = () => {
    setPage(0);
    void load();
  };

  const resetFilters = () => {
    setSearch('');
    setMinPopulation(''); setMaxPopulation('');
    setMinVillageCount(''); setMaxVillageCount('');
    setTribeIds([]);
    setNearVillageId(''); setMaxDistance('');
    setAllianceExclude(''); setAllianceInclude('');
    setSortBy('population'); setSortDir('desc');
    setPage(0);
  };

  const toggleTribe = (id: number) => {
    setTribeIds((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));
  };

  // Client-side name substring filter on the current page
  const itemsRaw = data?.items ?? [];
  const items = useMemo(() => {
    if (!search.trim()) return itemsRaw;
    const needle = search.trim().toLowerCase();
    return itemsRaw.filter((p) => p.name.toLowerCase().includes(needle));
  }, [itemsRaw, search]);

  const totalPages = data?.totalPages ?? 0;
  const total = data?.total ?? 0;

  const pivotVillage = useMemo(
    () => serverVillages.find((x) => x.id === Number(nearVillageId)) ?? null,
    [nearVillageId, serverVillages],
  );
  const pivotCoords = pivotVillage ? { x: pivotVillage.x, y: pivotVillage.y } : null;

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Igrači"
        subtitle="Sortiranje po populaciji / selima / rastu na trenutnom serveru."
      />
      </div>

      <ServerPickerCard serverId={serverId} onChange={setCurrentServer} />

      {serverId == null && (
        <Card>
          <p className="muted">Pick a Travian server above to start browsing players.</p>
        </Card>
      )}

      {serverId != null && (
        <>
          <Card title="Filters" hint="Hit Apply after editing — filters don't auto-submit.">
            <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(11rem, 1fr))', gap: '0.75rem' }}>
              <label className="form__field" style={{ gridColumn: 'span 2' }}>
                <span>Search name (current page)</span>
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="case-insensitive substring"
                />
              </label>
              <NumField label="Min population" v={minPopulation} set={setMinPopulation} min={0} max={50000} />
              <NumField label="Max population" v={maxPopulation} set={setMaxPopulation} min={0} max={50000} />
              <NumField label="Min village count" v={minVillageCount} set={setMinVillageCount} min={0} max={500} />
              <NumField label="Max village count" v={maxVillageCount} set={setMaxVillageCount} min={0} max={500} />

              <label className="form__field">
                <span>Near a village (optional)</span>
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
                        disabled={nearVillageId === ''} disabledTitle="Pick a pivot village first" />
            </div>

            <div className="form-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(14rem, 1fr))', gap: '0.75rem', marginTop: '0.5rem' }}>
              <label className="form__field">
                <span>Alliance includes</span>
                <input type="text" value={allianceInclude} onChange={(e) => setAllianceInclude(e.target.value)} placeholder="case-insensitive substring" />
              </label>
              <label className="form__field">
                <span>Alliance excludes</span>
                <input type="text" value={allianceExclude} onChange={(e) => setAllianceExclude(e.target.value)} placeholder="case-insensitive substring" />
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

            <div className="toolbar" style={{ gap: '0.75rem', marginTop: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-end' }}>
              <label className="form__field" style={{ minWidth: '10rem' }}>
                <span>Sort by</span>
                <select value={sortBy} onChange={(e) => setSortBy(e.target.value as InactiveSortBy)}>
                  {SORT_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
              </label>
              <label className="form__field" style={{ minWidth: '7rem' }}>
                <span>Direction</span>
                <select value={sortDir} onChange={(e) => setSortDir(e.target.value as SortDir)}>
                  <option value="asc">Ascending</option>
                  <option value="desc">Descending</option>
                </select>
              </label>
              <button type="button" className="btn btn--primary" onClick={apply} disabled={loading}>
                {loading ? 'Loading…' : 'Apply filters'}
              </button>
              <button type="button" className="btn btn--ghost" onClick={resetFilters}>Reset</button>
            </div>
          </Card>

          <Card
            title={`Results (${total})`}
            hint={search.trim() ? `Showing ${items.length} of ${itemsRaw.length} after client-side name filter.` : 'Click a row to see player\'s villages.'}
          >
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!error && loading && <p className="muted">Loading…</p>}
            {!error && !loading && items.length === 0 && (
              <p className="muted">
                No matches. Try widening the filters or hitting <em>Sync now</em> in
                Inactive Search to pull the latest map.
              </p>
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
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((row: InactivePlayer) => {
                      const deltaText =
                        row.populationDelta7d == null
                          ? '—'
                          : row.populationDelta7d > 0
                            ? `↑ ${row.populationDelta7d}`
                            : row.populationDelta7d < 0
                              ? `↓ ${Math.abs(row.populationDelta7d)}`
                              : '· 0';
                      return (
                        <tr key={row.id} onClick={() => setOpenUid(row.travianUid)} style={{ cursor: 'pointer' }} title="Click for villages">
                          <td><strong>{row.name}</strong></td>
                          <td>{row.allianceName ?? <span className="muted">—</span>}</td>
                          <td>{TRIBE_LABELS[row.tribeId] ?? row.tribeId}</td>
                          <td>{row.currentPopulation}</td>
                          <td className={row.populationDelta7d != null && row.populationDelta7d <= 0 ? 'muted' : ''}>{deltaText}</td>
                          <td>{row.villageCount}</td>
                          <td>{row.daysSincePopChange ?? <span className="muted">—</span>}</td>
                          <td>{row.nearestVillageDistance != null ? row.nearestVillageDistance.toFixed(1) : <span className="muted">—</span>}</td>
                          <td>
                            {row.isInactive
                              ? <span className="pill pill--success">Inactive</span>
                              : <span className="pill">Active</span>}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>

                {totalPages > 1 && (
                  <div className="toolbar" style={{ gap: '0.5rem', marginTop: '0.75rem', justifyContent: 'flex-end' }}>
                    <button type="button" className="btn btn--ghost"
                            onClick={() => setPage((p) => Math.max(0, p - 1))}
                            disabled={page === 0 || loading}>‹ Prev</button>
                    <span className="muted" style={{ alignSelf: 'center' }}>
                      Page {page + 1} of {totalPages}
                    </span>
                    <button type="button" className="btn btn--ghost"
                            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                            disabled={page >= totalPages - 1 || loading}>Next ›</button>
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
