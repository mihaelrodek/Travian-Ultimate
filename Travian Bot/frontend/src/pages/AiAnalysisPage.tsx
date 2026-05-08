import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { aiAnalysisApi } from '../api/aianalysis';
import type { OasisScan, OasisType } from '../types/aianalysis';
import { ApiException } from '../types/api';

/**
 * AI Analysis dashboard — runs an oasis scan around a center coordinate and
 * surfaces the per-resource counts + a usefulness score per tile.
 *
 * <h3>Usefulness score</h3>
 * The bot's weighting is intentionally simple so a glance tells the user
 * "this oasis is worth occupying":
 *   <ul>
 *     <li>Bonus % is the dominant factor (50% &gt; 25%).</li>
 *     <li>Distance penalty — closer is better (a 7-tile oasis is much more
 *         valuable than a 14-tile one because troops walk back faster).</li>
 *     <li>Crop oases get a small flat bonus — crop is the resource most
 *         likely to bottleneck a tribe.</li>
 *     <li>Already-occupied oases score zero (the bot can't take them).</li>
 *   </ul>
 *
 * <h3>Currently mock data</h3>
 * The backend's "scan" call returns a deterministic mock spread (seeded on
 * the {x, y, radius} tuple) until the Travian map endpoint is wired up.
 * Re-scanning the same coordinate yields the same tiles, which is good
 * enough to demo the dashboard. The page surfaces this as a small banner
 * so the user knows the data isn't real yet.
 */
export default function AiAnalysisPage() {
  const { account, accountId, loading: accountsLoading } = useCurrentAccount();

  const [latest, setLatest] = useState<OasisScan | null>(null);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Scan parameters. Defaults to {0, 0, 10} until the user has run at least
  // one scan; thereafter we prefill from the most recent scan so re-scan is
  // a single click.
  const [centerX, setCenterX] = useState(0);
  const [centerY, setCenterY] = useState(0);
  const [radius, setRadius] = useState(10);

  useEffect(() => {
    if (accountId == null) {
      setLatest(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    aiAnalysisApi
      .latest(accountId)
      .then((scan) => {
        if (cancelled) return;
        setLatest(scan);
        if (scan) {
          setCenterX(scan.centerX);
          setCenterY(scan.centerY);
          setRadius(scan.radius);
        }
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        // 404 = no scan yet, that's fine — keep the dashboard in its empty
        // state. Any other failure goes to the inline error banner.
        if (err instanceof ApiException && err.status === 404) {
          setLatest(null);
          setError(null);
        } else {
          setError(err instanceof Error ? err.message : 'Failed to load latest scan');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [accountId]);

  const onRunScan = async () => {
    if (accountId == null) return;
    setScanning(true);
    setError(null);
    try {
      const result = await aiAnalysisApi.scan(accountId, { centerX, centerY, radius });
      setLatest(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Scan failed');
    } finally {
      setScanning(false);
    }
  };

  // Sort + trim the tile list for the dashboard table — top 25 by usefulness
  // is a sensible default; the user can scroll the table for everything else.
  const rankedTiles = useMemo(() => {
    if (!latest) return [];
    return [...latest.tiles]
      .map((t) => ({ ...t, score: usefulness(t.type, t.bonusPct, t.distanceTiles, t.occupiedByPlayer != null) }))
      .sort((a, b) => b.score - a.score);
  }, [latest]);

  if (accountId == null && !accountsLoading) {
    return (
      <div className="stack stack--xwide">
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to run an analysis.
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack stack--xwide">
      <Card>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Analyses the map around a center coordinate, classifies every oasis by
          resource type + bonus, and ranks them by usefulness. The bot can use
          the result to prioritise oasis-conquest expansion — closer + higher-
          bonus + crop scores best.
        </p>
      </Card>

      <Card title="Run a new scan">
        <div className="form form--compact">
          <div className="form__field">
            <label>Center X</label>
            <input
              type="number"
              min={0}
              max={800}
              value={centerX}
              onChange={(e) => setCenterX(clampInt(e.target.value, 0, 800))}
              disabled={scanning}
            />
          </div>
          <div className="form__field">
            <label>Center Y</label>
            <input
              type="number"
              min={0}
              max={800}
              value={centerY}
              onChange={(e) => setCenterY(clampInt(e.target.value, 0, 800))}
              disabled={scanning}
            />
          </div>
          <div className="form__field">
            <label>Radius (tiles)</label>
            <input
              type="number"
              min={1}
              max={20}
              value={radius}
              onChange={(e) => setRadius(clampInt(e.target.value, 1, 20))}
              disabled={scanning}
            />
          </div>
          <div className="form__actions">
            <button
              type="button"
              className="btn btn--primary"
              onClick={onRunScan}
              disabled={scanning || loading}
            >
              {scanning ? 'Scanning…' : 'Run scan'}
            </button>
            {account && (
              <span className="muted" style={{ marginLeft: 'auto', fontSize: '0.85rem' }}>
                Account: <strong>{account.nickname || account.username}</strong>
                {account.serverUrl && <> · {new URL(account.serverUrl).hostname}</>}
              </span>
            )}
          </div>
        </div>
        {error && <p className="form__msg form__msg--error" style={{ marginTop: '0.5rem' }}>{error}</p>}
      </Card>

      {latest && (
        <>
          <Card>
            <p className="muted" style={{ margin: 0, fontSize: '0.8rem' }}>
              ⚠ Showing mock data. The Travian map endpoint isn't wired up yet — the bot will need to be
              taught how to fetch tiles around the village. Tiles are deterministic per (x, y, radius) so
              the dashboard layout stays stable while you design the real scrape.
            </p>
          </Card>

          <div className="ai-summary-grid">
            <SummaryCard label="Wood oases" count={latest.woodCount} colorClass="resource-tile--wood" />
            <SummaryCard label="Clay oases" count={latest.clayCount} colorClass="resource-tile--clay" />
            <SummaryCard label="Iron oases" count={latest.ironCount} colorClass="resource-tile--iron" />
            <SummaryCard label="Crop oases" count={latest.cropCount} colorClass="resource-tile--crop" />
            <SummaryCard label="Mixed oases" count={latest.mixedCount} colorClass="resource-tile--empty" />
            <SummaryCard
              label="Total in radius"
              count={latest.woodCount + latest.clayCount + latest.ironCount + latest.cropCount + latest.mixedCount}
              colorClass=""
            />
          </div>

          <Card title={`Ranked oases (${latest.tiles.length} tiles within ${latest.radius} of ${latest.centerX}|${latest.centerY})`}>
            {rankedTiles.length === 0 ? (
              <p className="muted">No oases found in this scan.</p>
            ) : (
              <table className="table table--compact">
                <thead>
                  <tr>
                    <th style={{ width: '4rem' }}>Score</th>
                    <th>Type</th>
                    <th>Bonus</th>
                    <th>Coords</th>
                    <th>Distance</th>
                    <th>Owner</th>
                  </tr>
                </thead>
                <tbody>
                  {rankedTiles.map((t) => (
                    <tr key={`${t.x}-${t.y}`}>
                      <td>
                        <span className="pill pill--info">{t.score.toFixed(1)}</span>
                      </td>
                      <td>
                        <span className={`oasis-pill oasis-pill--${t.type.toLowerCase()}`}>
                          {humanType(t.type)}
                        </span>
                      </td>
                      <td><strong>+{t.bonusPct}%</strong></td>
                      <td className="muted" style={{ fontVariantNumeric: 'tabular-nums' }}>
                        ({t.x}|{t.y})
                      </td>
                      <td className="muted" style={{ fontVariantNumeric: 'tabular-nums' }}>
                        {t.distanceTiles}
                      </td>
                      <td>
                        {t.occupiedByPlayer ? (
                          <span className="muted">{t.occupiedByPlayer}</span>
                        ) : (
                          <span className="pill pill--success" style={{ fontSize: '0.7rem' }}>free</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}

      {!latest && !loading && (
        <Card>
          <p className="muted">No scan yet — fill in a center coordinate and click <strong>Run scan</strong>.</p>
        </Card>
      )}
    </div>
  );
}

function SummaryCard({
  label,
  count,
  colorClass,
}: {
  label: string;
  count: number;
  colorClass: string;
}) {
  return (
    <div className={`ai-summary ${colorClass}`}>
      <div className="ai-summary__count">{count}</div>
      <div className="ai-summary__label muted">{label}</div>
    </div>
  );
}

/**
 * Heuristic "should I go take this oasis" score, 0..100. Roughly:
 *   - bonus 50% → 60 base, 25% → 30 base
 *   - mixed oasis → +10 (two resources at once)
 *   - crop oasis → +15 (the most likely bottleneck resource)
 *   - distance penalty → -2 per tile over 5
 *   - already occupied → 0 (out of reach)
 *
 * The numbers are deliberately tunable per-user later — exposing the
 * weights as settings is a future iteration.
 */
function usefulness(type: OasisType, bonusPct: number, distance: number, occupied: boolean): number {
  if (occupied) return 0;
  let score = bonusPct === 50 ? 60 : 30;
  if (type === 'MIXED') score += 10;
  if (type === 'CROP') score += 15;
  const distancePenalty = Math.max(0, distance - 5) * 2;
  score -= distancePenalty;
  return Math.max(0, Math.min(100, score));
}

function humanType(t: OasisType): string {
  switch (t) {
    case 'WOOD': return 'Wood';
    case 'CLAY': return 'Clay';
    case 'IRON': return 'Iron';
    case 'CROP': return 'Crop';
    case 'MIXED': return 'Mixed';
    case 'UNKNOWN': return '?';
  }
}

function clampInt(raw: string, min: number, max: number): number {
  const n = Number.parseInt(raw, 10);
  if (Number.isNaN(n)) return min;
  return Math.max(min, Math.min(max, n));
}
