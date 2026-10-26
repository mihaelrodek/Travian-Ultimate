import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import ServerPickerCard from '../features/servers/ServerPickerCard';
import { useCurrentServer } from '../hooks/useCurrentServer';
import { intelApi } from '../api/intel';
import type {
  Alliance,
  AlliancePage,
  AllianceSearchFilters,
  AllianceSortBy,
  SortDir,
} from '../types/intel';
import AllianceDetailModal from '../features/intel/AllianceDetailModal';
import InactivePlayerDetailModal from '../features/intel/InactivePlayerDetailModal';

// TODO i18n — strings in this page are hardcoded English.

/**
 * Alliances list page (server-scoped).
 *
 * Lists every alliance present in this server's most recent {@code map.sql}
 * aggregation (see backend {@code AllianceAggregator}) with name search,
 * member-count range and sortable columns. Click any row to open the
 * AllianceDetailModal, which lazy-loads description + bonuses + roster.
 *
 * The detail modal composes two more modals on top of itself:
 *  - a click on a roster row opens the existing {@link InactivePlayerDetailModal}
 *    (rendered above this page so its z-index is higher than the alliance modal);
 *  - a "drill to inactives" button navigates to /intel?alliance=N so the
 *    InactiveSearchPage can pre-fill its own alliance filter on mount.
 */

const PAGE_SIZE = 50;
const DEFAULT_MIN_MEMBERS = 5;
const DEFAULT_SORT_BY: AllianceSortBy = 'memberCount';
const DEFAULT_SORT_DIR: SortDir = 'desc';

const SORT_OPTIONS: Array<{ value: AllianceSortBy; label: string }> = [
  { value: 'name',              label: 'Name' },
  { value: 'memberCount',       label: 'Member count' },
  { value: 'totalPopulation',   label: 'Total population' },
  { value: 'averagePopulation', label: 'Average population' },
  { value: 'lastSeenAt',        label: 'Last seen' },
];

type NumberOrEmpty = number | '';

export default function AlliancesPage() {
  const navigate = useNavigate();
  const { server, serverId, setCurrentServer } = useCurrentServer();

  // --- Filter inputs (un-applied) ---
  const [search, setSearch] = useState<string>('');
  const [minMembers, setMinMembers] = useState<NumberOrEmpty>(DEFAULT_MIN_MEMBERS);
  const [maxMembers, setMaxMembers] = useState<NumberOrEmpty>('');
  const [sortBy, setSortBy] = useState<AllianceSortBy>(DEFAULT_SORT_BY);
  const [sortDir, setSortDir] = useState<SortDir>(DEFAULT_SORT_DIR);

  // Results / fetch state.
  const [page, setPage] = useState<number>(0);
  const [data, setData] = useState<AlliancePage | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Detail modal — open alliance + (optionally) open player on top of it.
  const [openAid, setOpenAid] = useState<number | null>(null);
  const [openPlayerUid, setOpenPlayerUid] = useState<number | null>(null);

  // Reset page + results whenever the server changes — alliance ids are
  // per-server so values from the previous world would be meaningless.
  useEffect(() => {
    setPage(0);
    setData(null);
    setError(null);
    setOpenAid(null);
    setOpenPlayerUid(null);
  }, [serverId]);

  const buildFilters = useCallback((): AllianceSearchFilters => {
    const num = (v: NumberOrEmpty): number | undefined => (v === '' ? undefined : Number(v));
    return {
      search: search.trim() || undefined,
      minMembers: num(minMembers),
      maxMembers: num(maxMembers),
      sortBy,
      sortDir,
      page,
      size: PAGE_SIZE,
    };
  }, [search, minMembers, maxMembers, sortBy, sortDir, page]);

  const load = useCallback(async () => {
    if (serverId == null) {
      setData(null);
      return;
    }
    setLoading(true);
    try {
      const resp = await intelApi.listAlliances(serverId, buildFilters());
      setData(resp);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load alliances');
    } finally {
      setLoading(false);
    }
  }, [serverId, buildFilters]);

  // Auto-load on server change + on page change (pagination). User-triggered
  // filter changes go through Apply, not auto-load, to avoid hammering the
  // backend on every keystroke in the name field.
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, page]);

  // Re-load when sort changes — sort is a click-the-header interaction so the
  // user expects it to fire immediately.
  useEffect(() => {
    if (serverId == null) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sortBy, sortDir]);

  const apply = () => {
    setPage(0);
    void load();
  };

  const resetFilters = () => {
    setSearch('');
    setMinMembers(DEFAULT_MIN_MEMBERS);
    setMaxMembers('');
    setSortBy(DEFAULT_SORT_BY);
    setSortDir(DEFAULT_SORT_DIR);
    setPage(0);
  };

  // Header-click sort: clicking the active column flips direction, clicking
  // a new column resets direction to the column-specific default (alphabetical
  // for name, descending for everything numeric).
  const toggleSort = (col: AllianceSortBy) => {
    if (col === sortBy) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortBy(col);
      setSortDir(col === 'name' ? 'asc' : 'desc');
    }
    setPage(0);
  };

  const items = data?.items ?? [];
  const totalPages = data?.totalPages ?? 0;
  const total = data?.total ?? 0;

  const drillToInactives = (aid: number, _name: string) => {
    // Close the modal so the user lands on the InactiveSearchPage with the
    // alliance filter pre-applied, not staring at a modal over the new page.
    setOpenAid(null);
    setOpenPlayerUid(null);
    navigate(`/intel?alliance=${aid}`);
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Savezi"
        subtitle="Klikni red za članove i bonuse saveza."
      />
      </div>

      <ServerPickerCard serverId={serverId} onChange={setCurrentServer} />

      {serverId == null && (
        <Card>
          <p className="muted">Pick a Travian server above to start browsing alliances.</p>
        </Card>
      )}

      {serverId != null && (
        <>
          <Card title="Filters" hint="Hit Apply after editing — filters don't auto-submit.">
            <div
              className="form-grid"
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(12rem, 1fr))',
                gap: '0.75rem',
              }}
            >
              <label className="form__field" style={{ gridColumn: 'span 2' }}>
                <span>Search by name</span>
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') apply();
                  }}
                  placeholder="case-insensitive substring"
                />
              </label>

              <NumField
                label="Min members"
                v={minMembers}
                set={setMinMembers}
                min={0}
                max={500}
              />
              <NumField
                label="Max members"
                v={maxMembers}
                set={setMaxMembers}
                min={0}
                max={500}
              />

              <label className="form__field">
                <span>Sort by</span>
                <select
                  value={sortBy}
                  onChange={(e) => {
                    setSortBy(e.target.value as AllianceSortBy);
                    setPage(0);
                  }}
                >
                  {SORT_OPTIONS.map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </select>
              </label>
              <label className="form__field">
                <span>Direction</span>
                <select
                  value={sortDir}
                  onChange={(e) => {
                    setSortDir(e.target.value as SortDir);
                    setPage(0);
                  }}
                >
                  <option value="asc">Ascending</option>
                  <option value="desc">Descending</option>
                </select>
              </label>
            </div>

            <div
              className="toolbar"
              style={{ gap: '0.5rem', marginTop: '0.75rem', flexWrap: 'wrap' }}
            >
              <button
                type="button"
                className="btn btn--primary"
                onClick={apply}
                disabled={loading}
              >
                {loading ? 'Loading…' : 'Apply filters'}
              </button>
              <button type="button" className="btn btn--ghost" onClick={resetFilters}>
                Reset
              </button>
            </div>
          </Card>

          <Card
            title={`Results (${total})`}
            hint="Click a row for full alliance view. Column headers are sortable."
          >
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!error && loading && <p className="muted">Loading…</p>}
            {!error && !loading && items.length === 0 && (
              <p className="muted">
                No alliances match. Try widening the filters or hitting{' '}
                <em>Sync now</em> on the Inactive search page to refresh the map.
              </p>
            )}
            {!error && items.length > 0 && (
              <>
                <table className="table">
                  <thead>
                    <tr>
                      <SortHeader label="Name" col="name" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Members" col="memberCount" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Total pop" col="totalPopulation" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Avg pop" col="averagePopulation" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Last seen" col="lastSeenAt" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <th>Details scraped at</th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((row: Alliance) => (
                      <tr
                        key={row.id}
                        onClick={() => setOpenAid(row.travianAid)}
                        style={{ cursor: 'pointer' }}
                        title="Click for full alliance view"
                      >
                        <td><strong>{row.name}</strong></td>
                        <td>{row.memberCount}</td>
                        <td>{row.totalPopulation.toLocaleString()}</td>
                        <td>{Math.round(row.averagePopulation).toLocaleString()}</td>
                        <td className="muted" style={{ fontSize: '0.85rem' }}>{fmtDate(row.lastSeenAt)}</td>
                        <td className="muted" style={{ fontSize: '0.85rem' }}>
                          {row.lastDetailScrapedAt
                            ? fmtDate(row.lastDetailScrapedAt)
                            : <span className="muted">— never —</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                {totalPages > 1 && (
                  <div
                    className="toolbar"
                    style={{ gap: '0.5rem', marginTop: '0.75rem', justifyContent: 'flex-end' }}
                  >
                    <button
                      type="button"
                      className="btn btn--ghost"
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                      disabled={page === 0 || loading}
                    >
                      ‹ Prev
                    </button>
                    <span className="muted" style={{ alignSelf: 'center' }}>
                      Page {page + 1} of {totalPages}
                    </span>
                    <button
                      type="button"
                      className="btn btn--ghost"
                      onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                      disabled={page >= totalPages - 1 || loading}
                    >
                      Next ›
                    </button>
                  </div>
                )}
              </>
            )}
          </Card>

          {/* Alliance modal — rendered first so the player modal stacks on top. */}
          {openAid != null && (
            <AllianceDetailModal
              serverId={serverId}
              aid={openAid}
              serverUrl={server?.url ?? null}
              onClose={() => setOpenAid(null)}
              onOpenPlayer={(uid) => setOpenPlayerUid(uid)}
              onDrillToInactives={drillToInactives}
            />
          )}

          {/* Player modal renders on top because it mounts after — both share
              z-index: 1000 but later DOM order wins for opaque overlays. */}
          {openPlayerUid != null && (
            <InactivePlayerDetailModal
              serverId={serverId}
              uid={openPlayerUid}
              serverUrl={server?.url ?? null}
              onClose={() => setOpenPlayerUid(null)}
            />
          )}
        </>
      )}
    </div>
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
      {hint && !disabled && (
        <small className="muted" style={{ fontSize: '0.7rem' }}>{hint}</small>
      )}
    </label>
  );
}

function SortHeader({
  label, col, sortBy, sortDir, onToggle,
}: {
  label: string;
  col: AllianceSortBy;
  sortBy: AllianceSortBy;
  sortDir: SortDir;
  onToggle: (c: AllianceSortBy) => void;
}) {
  const active = sortBy === col;
  return (
    <th
      onClick={() => onToggle(col)}
      style={{ cursor: 'pointer', userSelect: 'none' }}
      title={`Sort by ${label}`}
    >
      {label}
      {active && (
        <span aria-hidden style={{ marginLeft: 4 }}>{sortDir === 'asc' ? '▲' : '▼'}</span>
      )}
    </th>
  );
}

function fmtDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
