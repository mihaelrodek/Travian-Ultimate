import { useEffect, useMemo, useState } from 'react';
import { intelApi } from '../../api/intel';
import type { AllianceDetail, AllianceMember } from '../../types/intel';

// TODO i18n — strings in this component are hardcoded English.

type Props = {
  /**
   * Travian world the alliance belongs to. The detail call is server-scoped
   * (alliances are per-world).
   */
  serverId: number;
  /** Travian aid — used in {@code /allianz.php?aid=N} links. */
  aid: number;
  /** Server URL of the world, used for building "Open in Travian" links. */
  serverUrl?: string | null;
  onClose: () => void;
  /**
   * Open a player detail when the user clicks a roster row. The parent page
   * is responsible for actually rendering the player modal — keeping that
   * composition on the parent avoids forcing this modal to know how to wire
   * up an `InactivePlayerDetailModal`.
   */
  onOpenPlayer?: (uid: number) => void;
  /**
   * "Filter inactive search by this alliance" — used by the parent page to
   * navigate the user to {@code /intel?alliance=N} so the InactiveSearchPage
   * can pre-fill its alliance filter.
   */
  onDrillToInactives?: (aid: number, name: string) => void;
};

type SortBy = 'name' | 'population' | 'role';
type SortDir = 'asc' | 'desc';

/**
 * Modal panel showing one alliance's full profile + bonuses + roster.
 * Opens on row click in the AlliancesPage results table.
 *
 * Sections rendered top to bottom:
 *  1. Header — name, member count, total population pills + close button.
 *  2. Description — plain text, escaped via React's default text rendering.
 *  3. Bonuses — 4-column grid (Diplomacy / Mining / Scout / Brewery), plus
 *     a small key/value list for anything in {@code rawBonusesJson} we don't
 *     model as a first-class field.
 *  4. Members — sortable table, defaults to population desc. Clicking a
 *     player name fires {@link Props.onOpenPlayer} so the parent can open
 *     the existing {@code InactivePlayerDetailModal} on top.
 *  5. Footer — refresh / open in Travian / drill-to-inactives / close.
 */
export default function AllianceDetailModal({
  serverId,
  aid,
  serverUrl,
  onClose,
  onOpenPlayer,
  onDrillToInactives,
}: Props) {
  const [detail, setDetail] = useState<AllianceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshMsg, setRefreshMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const [sortBy, setSortBy] = useState<SortBy>('population');
  const [sortDir, setSortDir] = useState<SortDir>('desc');

  // Initial load — always the cached row. Caller can hit "Refresh details" to
  // re-scrape if `lastDetailScrapedAt` is null or stale.
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    intelApi
      .getAlliance(serverId, aid)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load alliance');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [serverId, aid]);

  // Close on Escape — matches InactivePlayerDetailModal behaviour.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const refresh = async () => {
    setRefreshing(true);
    setRefreshMsg(null);
    try {
      const fresh = await intelApi.refreshAllianceDetail(serverId, aid, true);
      setDetail(fresh);
      setRefreshMsg({
        kind: 'ok',
        text: `Roster + bonuses refreshed (${fresh.members.length} member${fresh.members.length === 1 ? '' : 's'}).`,
      });
    } catch (err) {
      setRefreshMsg({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Refresh failed',
      });
    } finally {
      setRefreshing(false);
    }
  };

  const allianceUrl = useMemo(
    () => (serverUrl ? `${serverUrl.replace(/\/$/, '')}/allianz.php?aid=${aid}` : null),
    [serverUrl, aid],
  );

  const sortedMembers = useMemo<AllianceMember[]>(() => {
    if (!detail) return [];
    const arr = [...detail.members];
    arr.sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      switch (sortBy) {
        case 'name':
          return dir * a.name.localeCompare(b.name);
        case 'role': {
          // Sort by a stable role bucket index, then by name as a secondary key.
          const ra = roleBucket(a.role);
          const rb = roleBucket(b.role);
          if (ra !== rb) return dir * (ra - rb);
          return a.name.localeCompare(b.name);
        }
        case 'population':
        default:
          return dir * (a.population - b.population);
      }
    });
    return arr;
  }, [detail, sortBy, sortDir]);

  /**
   * Defensive JSON.parse for the extra-bonuses payload. The backend may emit
   * an empty string, null, or a malformed blob if the scraper hit a strange
   * page — we render nothing in any of those cases rather than crashing the
   * whole modal.
   */
  const extraBonuses: Array<[string, number]> = useMemo(() => {
    if (!detail?.rawBonusesJson) return [];
    try {
      const parsed = JSON.parse(detail.rawBonusesJson) as Record<string, number>;
      if (parsed == null || typeof parsed !== 'object') return [];
      return Object.entries(parsed)
        .filter(([, level]) => typeof level === 'number')
        .filter(([name]) => {
          // Hide entries already shown in the main grid — backend may include
          // them in the raw blob for round-trip fidelity.
          const k = name.trim().toLowerCase();
          return k !== 'diplomacy' && k !== 'mining' && k !== 'scout' && k !== 'brewery';
        });
    } catch {
      return [];
    }
  }, [detail?.rawBonusesJson]);

  const toggleSort = (col: SortBy) => {
    if (col === sortBy) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortBy(col);
      setSortDir(col === 'name' ? 'asc' : 'desc');
    }
  };

  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
        zIndex: 1000, padding: '2rem', overflowY: 'auto',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--color-surface, #fff)', borderRadius: '8px',
          width: 'min(960px, 100%)', boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
          padding: '1.5rem',
        }}
      >
        {/* ---------- Header ---------- */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
          <div>
            <h2 style={{ margin: 0 }}>
              {detail?.name ?? 'Loading…'}
              {detail && (
                <>
                  <span className="pill" style={{ marginLeft: '0.5rem' }}>
                    {detail.memberCount} member{detail.memberCount === 1 ? '' : 's'}
                  </span>
                  <span className="pill" style={{ marginLeft: '0.25rem' }}>
                    {detail.totalPopulation.toLocaleString()} pop
                  </span>
                </>
              )}
            </h2>
            {detail && (
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.875rem' }}>
                aid {detail.travianAid}
                {' · avg pop '}{Math.round(detail.averagePopulation).toLocaleString()}
                {' · first seen '}{fmtDate(detail.firstSeenAt)}
                {' · last seen '}{fmtDate(detail.lastSeenAt)}
                {' · details '}
                {detail.lastDetailScrapedAt ? fmtDate(detail.lastDetailScrapedAt) : 'never scraped'}
              </p>
            )}
          </div>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {loading && <p className="muted">Loading…</p>}
        {error && <p className="form__msg form__msg--error">{error}</p>}

        {detail && (
          <>
            {/* Stale-cache hint: only shows when nothing's been scraped yet. */}
            {detail.lastDetailScrapedAt == null && (
              <p
                className="form__msg"
                style={{ marginBottom: '0.75rem' }}
              >
                Click <strong>Refresh details</strong> to fetch this alliance's roster
                and bonuses from the server.
              </p>
            )}

            {/* ---------- Description ---------- */}
            <section style={{ marginBottom: '1rem' }}>
              <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>Description</h3>
              {detail.description ? (
                // React's default text rendering escapes HTML for us, so a
                // malicious description can't inject markup.
                <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{detail.description}</p>
              ) : (
                <p className="muted" style={{ margin: 0 }}>
                  {detail.lastDetailScrapedAt
                    ? 'No description set.'
                    : '— (not scraped yet)'}
                </p>
              )}
            </section>

            {/* ---------- Bonuses ---------- */}
            <section style={{ marginBottom: '1rem' }}>
              <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>Bonuses</h3>
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
                  gap: '0.5rem',
                }}
              >
                <BonusTile label="Diplomacy" level={detail.bonusDiplomacy} />
                <BonusTile label="Mining" level={detail.bonusMining} />
                <BonusTile label="Scout" level={detail.bonusScout} />
                <BonusTile label="Brewery" level={detail.bonusBrewery} />
              </div>
              {extraBonuses.length > 0 && (
                <div style={{ marginTop: '0.5rem' }}>
                  <small className="muted" style={{ fontSize: '0.75rem' }}>
                    Other bonuses
                  </small>
                  <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem', fontSize: '0.85rem' }}>
                    {extraBonuses.map(([name, level]) => (
                      <li key={name}>
                        {name}: <strong>{level}</strong>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </section>

            {/* ---------- Members ---------- */}
            <section style={{ marginBottom: '1rem' }}>
              <h3 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>
                Members ({detail.members.length})
              </h3>
              {detail.members.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>
                  Roster empty.{' '}
                  {detail.lastDetailScrapedAt == null
                    ? 'Hit Refresh details to scrape it.'
                    : 'The scraper returned no members on the last run.'}
                </p>
              ) : (
                <table className="table">
                  <thead>
                    <tr>
                      <SortHeader label="Player" col="name" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Population" col="population" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                      <SortHeader label="Role" col="role" sortBy={sortBy} sortDir={sortDir} onToggle={toggleSort} />
                    </tr>
                  </thead>
                  <tbody>
                    {sortedMembers.map((m) => (
                      <tr key={m.uid}>
                        <td>
                          {onOpenPlayer ? (
                            <button
                              type="button"
                              className="link"
                              onClick={() => onOpenPlayer(m.uid)}
                              style={{
                                background: 'none', border: 'none', padding: 0,
                                cursor: 'pointer', font: 'inherit', textAlign: 'left',
                              }}
                              title="Open player detail"
                            >
                              {m.name}
                            </button>
                          ) : (
                            m.name
                          )}
                        </td>
                        <td>{m.population.toLocaleString()}</td>
                        <td>
                          {m.role
                            ? <span className={roleClass(m.role)}>{m.role}</span>
                            : <span className="muted">—</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            {/* ---------- Refresh message + footer actions ---------- */}
            {refreshMsg && (
              <p
                className={
                  refreshMsg.kind === 'ok'
                    ? 'form__msg form__msg--success'
                    : 'form__msg form__msg--error'
                }
                style={{ marginBottom: '0.75rem' }}
              >
                {refreshMsg.text}
              </p>
            )}
            <div
              className="toolbar"
              style={{ gap: '0.5rem', flexWrap: 'wrap', justifyContent: 'flex-end' }}
            >
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => void refresh()}
                disabled={refreshing}
                title="Re-scrape description, bonuses and roster from the live server"
              >
                {refreshing ? 'Refreshing…' : 'Refresh details'}
              </button>
              {onDrillToInactives && (
                <button
                  type="button"
                  className="btn btn--ghost"
                  onClick={() => onDrillToInactives(detail.travianAid, detail.name)}
                  title="Open Inactive search filtered to this alliance"
                >
                  Show inactive players in this alliance
                </button>
              )}
              {allianceUrl && (
                <a
                  className="btn btn--ghost"
                  href={allianceUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  Open alliance in Travian ↗
                </a>
              )}
              <button type="button" className="btn btn--primary" onClick={onClose}>
                Close
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function BonusTile({ label, level }: { label: string; level: number | null }) {
  const present = level != null && level > 0;
  return (
    <div
      style={{
        padding: '0.5rem 0.75rem',
        background: 'var(--color-surface-2, rgba(0,0,0,0.04))',
        borderRadius: 4,
        textAlign: 'center',
      }}
    >
      <div
        className="muted"
        style={{ fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: 0.5 }}
      >
        {label}
      </div>
      <div className={present ? '' : 'muted'} style={{ fontSize: '1.1rem', fontWeight: 600 }}>
        {level == null ? '—' : level}
      </div>
    </div>
  );
}

function SortHeader({
  label, col, sortBy, sortDir, onToggle,
}: {
  label: string;
  col: SortBy;
  sortBy: SortBy;
  sortDir: SortDir;
  onToggle: (c: SortBy) => void;
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

/**
 * Map a free-form role string to a pill class so leaders / side leaders are
 * visually distinct from rank-and-file members. Matching is intentionally
 * case-insensitive substring — different worlds / languages spell roles
 * slightly differently and we don't want to fall back to "muted" for every
 * non-English alliance.
 */
function roleClass(role: string): string {
  const r = role.trim().toLowerCase();
  if (r.includes('leader') && !r.includes('side')) return 'pill pill--warn';
  if (r.includes('side')) return 'pill';
  // "Member" and everything else → muted pill.
  return 'pill pill--muted';
}

/** Sort key for roles — leader first, side leader next, rest last. */
function roleBucket(role: string | null): number {
  if (!role) return 99;
  const r = role.trim().toLowerCase();
  if (r.includes('leader') && !r.includes('side')) return 0;
  if (r.includes('side')) return 1;
  return 2;
}

function fmtDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
