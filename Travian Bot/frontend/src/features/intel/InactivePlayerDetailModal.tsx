import { Fragment, useEffect, useState } from 'react';
import { intelApi } from '../../api/intel';
import { TRIBE_LABELS, type InactivePlayerDetail, type VillagePopulationPoint } from '../../types/intel';
import { getScoutTroop } from '../../data/travianTroops';

type Props = {
  /**
   * Travian world the player belongs to. The detail call is server-scoped
   * (the map.sql dataset is per-world, shared across all accounts on it).
   */
  serverId: number;
  uid: number;
  /** Server URL of the world, used for building "Open in Travian" links. */
  serverUrl?: string | null;
  /** Pivot village coords for distance calc per village. Optional. */
  pivot?: { x: number; y: number } | null;
  onClose: () => void;
};

/**
 * Modal panel showing one player's full profile + every village we know about.
 * Opens on row click in the InactiveSearchPage results table.
 *
 * Sections:
 *  1. Header — name, tribe, alliance, status, "Open in Travian" link.
 *  2. Quick metrics — current pop, village count, Δ7d, Δ30d, last pop change.
 *  3. Villages table — each row has actions (chart toggle, raid, scout, map).
 *     Click the 📈 icon to expand an inline sparkline of pop history.
 */
export default function InactivePlayerDetailModal({
  serverId, uid, serverUrl, pivot, onClose,
}: Props) {
  const [detail, setDetail] = useState<InactivePlayerDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /** Map villageId → expanded chart data (null = loading, [] = no data yet). */
  const [historyByVillage, setHistoryByVillage] = useState<Record<number, VillagePopulationPoint[] | null>>({});

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    intelApi.getPlayer(serverId, uid)
      .then((d) => { if (!cancelled) setDetail(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [serverId, uid]);

  // Close on Escape
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const profileUrl = serverUrl ? `${serverUrl.replace(/\/$/, '')}/spieler.php?uid=${uid}` : null;
  const allianceUrl = serverUrl && detail?.allianceId
    ? `${serverUrl.replace(/\/$/, '')}/allianz.php?aid=${detail.allianceId}`
    : null;

  const delta7 = detail && detail.populationSnapshot7dAgo != null
    ? detail.currentPopulation - detail.populationSnapshot7dAgo : null;
  const delta30 = detail && detail.populationSnapshot30dAgo != null
    ? detail.currentPopulation - detail.populationSnapshot30dAgo : null;

  const distance = (vx: number, vy: number) =>
    pivot ? Math.sqrt((vx - pivot.x) ** 2 + (vy - pivot.y) ** 2) : null;

  // Scout unit for this player's tribe (Pathfinder for Gauls, Equites Legati
  // for Romans, etc.). Used to label the Scout button and pre-fill the URL
  // with the right `troop[tN]=` field so the rally point opens with the
  // count hint on tribes that respect it.
  const scoutTroop = detail ? getScoutTroop(detail.tribeId) : null;
  /** Default scout count to pre-fill. Conservative — enough for one village
   *  scout, low enough not to bleed troops if user forgets to edit. */
  const DEFAULT_SCOUT_COUNT = 5;

  const toggleHistory = async (mapVillageId: number) => {
    if (historyByVillage[mapVillageId] !== undefined) {
      // already loaded (or loading) → collapse
      const { [mapVillageId]: _, ...rest } = historyByVillage;
      setHistoryByVillage(rest);
      return;
    }
    setHistoryByVillage((s) => ({ ...s, [mapVillageId]: null })); // loading marker
    try {
      const points = await intelApi.villagePopulationHistory(serverId, mapVillageId, 30);
      setHistoryByVillage((s) => ({ ...s, [mapVillageId]: points }));
    } catch {
      setHistoryByVillage((s) => ({ ...s, [mapVillageId]: [] })); // mark as failed/empty
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
          width: 'min(900px, 100%)', boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
          padding: '1.5rem',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '1rem' }}>
          <div>
            <h2 style={{ margin: 0 }}>
              {detail?.name ?? 'Loading…'}
              {detail?.isInactive && <span className="pill pill--success" style={{ marginLeft: '0.5rem' }}>Inactive</span>}
            </h2>
            {detail && (
              <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.875rem' }}>
                {TRIBE_LABELS[detail.tribeId] ?? `Tribe ${detail.tribeId}`} ·{' '}
                {detail.allianceName ? (
                  allianceUrl
                    ? <a className="link" href={allianceUrl} target="_blank" rel="noreferrer">{detail.allianceName}</a>
                    : detail.allianceName
                ) : 'No alliance'}
                {' · uid '}{detail.travianUid}
              </p>
            )}
          </div>
          <button type="button" className="btn btn--ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {loading && <p className="muted">Loading…</p>}
        {error && <p className="form__msg form__msg--error">{error}</p>}

        {detail && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(10rem, 1fr))', gap: '0.75rem', marginBottom: '1rem' }}>
              <Metric label="Population" value={detail.currentPopulation.toLocaleString()} />
              <Metric label="Villages" value={String(detail.villageCount)} />
              <Metric label="Δ 7d" value={fmtDelta(delta7)} muted={delta7 == null || delta7 <= 0} />
              <Metric label="Δ 30d" value={fmtDelta(delta30)} muted={delta30 == null || delta30 <= 0} />
              <Metric label="Last pop change" value={detail.lastPopChangeAt ? fmtDate(detail.lastPopChangeAt) : '—'} />
            </div>

            <div style={{ marginBottom: '1rem' }}>
              <h3 style={{ margin: '0 0 0.5rem' }}>Villages ({detail.villages.length})</h3>
              {detail.villages.length === 0 ? (
                <p className="muted">No villages tracked. Run a Sync.</p>
              ) : (
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Coords</th>
                      <th>Pop</th>
                      <th>Type</th>
                      {pivot && <th>Distance</th>}
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {[...detail.villages]
                      .sort((a, b) => {
                        if (pivot) {
                          const da = distance(a.x, a.y) ?? Infinity;
                          const db = distance(b.x, b.y) ?? Infinity;
                          return da - db;
                        }
                        return b.population - a.population;
                      })
                      .map((v) => {
                        const d = distance(v.x, v.y);
                        const base = serverUrl ? serverUrl.replace(/\/$/, '') : null;
                        const tileUrl = base ? `${base}/karte.php?x=${v.x}&y=${v.y}` : null;
                        // Travian rally-point pre-fill URLs. eventType:
                        //   4 = normal attack, 5 = raid, 6 = scout.
                        const mapId = travianMapId(v.x, v.y);
                        const sendUrl = base
                          ? `${base}/build.php?gid=16&tt=2&eventType=5&targetMapId=${mapId}`
                          : null;
                        // Scout URL — tribe-aware. We append `troop[tN]=count`
                        // as a GET-param hint; some Travian versions accept
                        // it as a pre-fill, others ignore it. Either way the
                        // user lands on the rally point ready for input.
                        const scoutUrl = base && scoutTroop
                          ? `${base}/build.php?gid=16&tt=2&eventType=6&targetMapId=${mapId}`
                              + `&troop%5Bt${scoutTroop.tIndex}%5D=${DEFAULT_SCOUT_COUNT}`
                          : null;
                        const history = historyByVillage[v.id];
                        const historyOpen = history !== undefined;
                        return (
                          <Fragment key={v.travianVid}>
                            <tr>
                              <td>{v.name}</td>
                              <td>
                                {tileUrl ? (
                                  <a className="link" href={tileUrl} target="_blank" rel="noreferrer">
                                    ({v.x}|{v.y})
                                  </a>
                                ) : `(${v.x}|${v.y})`}
                              </td>
                              <td>{v.population}</td>
                              <td>{v.isCapital
                                ? <span className="pill pill--warn">Capital</span>
                                : <span className="muted">—</span>}</td>
                              {pivot && (
                                <td>{d != null ? d.toFixed(1) : <span className="muted">—</span>}</td>
                              )}
                              <td>
                                <div className="toolbar" style={{ gap: '0.25rem', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                                  <button
                                    type="button"
                                    className={historyOpen ? 'btn btn--primary' : 'btn btn--ghost'}
                                    onClick={() => void toggleHistory(v.id)}
                                    title={historyOpen ? 'Hide population history' : 'Show population history'}
                                  >
                                    📈
                                  </button>
                                  {sendUrl && (
                                    <a
                                      className="btn btn--ghost"
                                      href={sendUrl}
                                      target="_blank"
                                      rel="noreferrer"
                                      title={`Open rally point pre-filled for a raid on (${v.x}|${v.y}) — mapId ${mapId}`}
                                    >
                                      ⚔ Send
                                    </a>
                                  )}
                                  {scoutUrl && (
                                    <a
                                      className="btn btn--ghost"
                                      href={scoutUrl}
                                      target="_blank"
                                      rel="noreferrer"
                                      title={`Open rally point pre-filled for ${DEFAULT_SCOUT_COUNT} ${scoutTroop?.name ?? 'scouts'} → (${v.x}|${v.y})`}
                                    >
                                      👁 {scoutTroop?.name ?? 'Scout'}
                                    </a>
                                  )}
                                  {tileUrl && (
                                    <a className="btn btn--ghost" href={tileUrl} target="_blank" rel="noreferrer" title="Open this tile on the in-game map">
                                      🗺
                                    </a>
                                  )}
                                </div>
                              </td>
                            </tr>
                            {historyOpen && (
                              <tr>
                                <td colSpan={pivot ? 6 : 5} style={{ background: 'rgba(0,0,0,0.03)', padding: '0.75rem' }}>
                                  <PopulationSparkline data={history} currentPop={v.population} />
                                </td>
                              </tr>
                            )}
                          </Fragment>
                        );
                      })}
                  </tbody>
                </table>
              )}
            </div>

            <div className="toolbar" style={{ gap: '0.5rem', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              {profileUrl && (
                <a className="btn btn--ghost" href={profileUrl} target="_blank" rel="noreferrer">
                  Open profile in Travian ↗
                </a>
              )}
              <button type="button" className="btn btn--primary" onClick={onClose}>Close</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Metric({ label, value, muted }: { label: string; value: string; muted?: boolean }) {
  return (
    <div style={{ padding: '0.5rem 0.75rem', background: 'var(--color-surface-2, rgba(0,0,0,0.04))', borderRadius: 4 }}>
      <div className="muted" style={{ fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: 0.5 }}>{label}</div>
      <div className={muted ? 'muted' : ''} style={{ fontSize: '1rem', fontWeight: 600 }}>{value}</div>
    </div>
  );
}

/**
 * Inline SVG sparkline for a village's population history. Renders nothing
 * fancy — just a polyline with dot markers. Reads `data` (oldest first when
 * sorted) and falls back to "loading" / "no history yet" messages.
 *
 * The backend returns newest-first; we reverse here so the line reads
 * left-to-right (oldest → newest), like every other chart in the world.
 */
function PopulationSparkline({ data, currentPop }: { data: VillagePopulationPoint[] | null; currentPop: number }) {
  if (data === null) {
    return <p className="muted" style={{ margin: 0 }}>Loading history…</p>;
  }
  if (data.length === 0) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        No history yet — we record a snapshot the next time this village's pop changes.
        Current pop is <strong>{currentPop}</strong>.
      </p>
    );
  }
  const sorted = [...data].sort((a, b) =>
    new Date(a.capturedAt).getTime() - new Date(b.capturedAt).getTime());

  const W = 760;
  const H = 90;
  const PAD_X = 28;
  const PAD_Y = 12;
  const innerW = W - PAD_X * 2;
  const innerH = H - PAD_Y * 2;

  const pops = sorted.map((p) => p.population);
  const min = Math.min(...pops);
  const max = Math.max(...pops);
  const span = Math.max(1, max - min);
  const xs = sorted.map((_, i) => sorted.length === 1 ? PAD_X + innerW / 2
    : PAD_X + (i / (sorted.length - 1)) * innerW);
  const ys = sorted.map((p) => PAD_Y + (1 - (p.population - min) / span) * innerH);
  const linePath = xs.map((x, i) => `${i === 0 ? 'M' : 'L'} ${x} ${ys[i]}`).join(' ');

  const firstAt = new Date(sorted[0].capturedAt).toLocaleDateString();
  const lastAt = new Date(sorted[sorted.length - 1].capturedAt).toLocaleDateString();

  return (
    <div>
      <div className="muted" style={{ fontSize: '0.75rem', marginBottom: '0.25rem' }}>
        Population history · {sorted.length} snapshot{sorted.length === 1 ? '' : 's'} ·
        {' '}{firstAt} → {lastAt} · range {min}–{max}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: H, display: 'block' }}>
        <path d={linePath} fill="none" stroke="currentColor" strokeWidth={1.5} />
        {xs.map((x, i) => (
          <circle key={i} cx={x} cy={ys[i]} r={2.5} fill="currentColor">
            <title>
              {new Date(sorted[i].capturedAt).toLocaleString()}: {sorted[i].population}
            </title>
          </circle>
        ))}
        <text x={PAD_X - 4} y={PAD_Y + 4} textAnchor="end" fontSize={10} fill="currentColor" opacity={0.6}>{max}</text>
        <text x={PAD_X - 4} y={H - PAD_Y + 4} textAnchor="end" fontSize={10} fill="currentColor" opacity={0.6}>{min}</text>
      </svg>
    </div>
  );
}

function fmtDelta(n: number | null): string {
  if (n == null) return '—';
  if (n > 0) return `↑ ${n}`;
  if (n < 0) return `↓ ${Math.abs(n)}`;
  return '· 0';
}

function fmtDate(iso: string): string {
  try { return new Date(iso).toLocaleString(); }
  catch { return iso; }
}

/**
 * Convert (x, y) coordinates to Travian's internal mapId used in rally-point
 * deep-links like {@code /build.php?gid=16&tt=2&eventType=5&targetMapId=N}.
 *
 * Standard T4.6 worlds are 401×401 (radius 200). The map is scanned row-by-row
 * from top-left:
 *   - top-left tile (-200, +200) is mapId 1
 *   - each step +1 on x increases mapId by 1
 *   - each step -1 on y skips one full row (401 ids)
 *
 * Formula: {@code mapId = (radius - y) * (2*radius + 1) + (x + radius) + 1}.
 *
 * Verified against:
 *   (-37, 21) → 71943
 *   (-36, 21) → 71944
 *
 * Non-standard world sizes (x10 sprint worlds use a smaller radius) would
 * need {@code radius} passed in. TODO: derive the per-server radius from
 * {@code MapVillage} bounds once we have enough data and expose it via prop.
 */
function travianMapId(x: number, y: number, radius = 200): number {
  const width = 2 * radius + 1;
  return (radius - y) * width + (x + radius) + 1;
}
