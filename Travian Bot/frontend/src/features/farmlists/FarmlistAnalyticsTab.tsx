import { Fragment, useEffect, useMemo, useState } from 'react';
import Card from '../../components/Card';
import { farmlistsApi } from '../../api/farmlists';
import type { FarmlistTargetMetric, RaidReport } from '../../types/farmlist';

interface Props {
  accountId: number;
  farmlistId: number;
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; metrics: FarmlistTargetMetric[] }
  | { kind: 'error'; message: string };

type ScrapeState =
  | { kind: 'idle' }
  | { kind: 'scraping' }
  | { kind: 'done'; scraped: number }
  | { kind: 'error'; message: string };

/**
 * Analytics view for a single farmlist.
 * <p>
 * Renders three panels:
 * <ul>
 *   <li>A red banner listing prune-candidates (RED traffic light + ≥3 consecutive losses).</li>
 *   <li>A summary row with totals + prune count.</li>
 *   <li>A per-target table — click a row to expand and load the last 5 reports for it.</li>
 * </ul>
 * Data freshness is driven by the "Scrape reports now" button — automatic
 * scraping happens server-side every 10 minutes, this is the manual trigger.
 */
export default function FarmlistAnalyticsTab({ accountId, farmlistId }: Props) {
  const [state, setState] = useState<LoadState>({ kind: 'loading' });
  const [scrape, setScrape] = useState<ScrapeState>({ kind: 'idle' });
  const [expandedTargetId, setExpandedTargetId] = useState<number | null>(null);
  const [expandedReports, setExpandedReports] = useState<RaidReport[]>([]);
  const [expandLoading, setExpandLoading] = useState(false);

  // Reload metrics from the backend. Called on mount + after a scrape returns.
  const reload = () => {
    setState({ kind: 'loading' });
    farmlistsApi
      .getFarmlistMetrics(accountId, farmlistId)
      .then((metrics) => setState({ kind: 'ready', metrics }))
      .catch((err: unknown) =>
        setState({ kind: 'error', message: err instanceof Error ? err.message : 'Failed to load metrics' }),
      );
  };

  useEffect(() => {
    reload();
    // Run reload again only when account/farmlist changes — manual scrapes
    // call it explicitly via onScrapeNow.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, farmlistId]);

  const onScrapeNow = async () => {
    setScrape({ kind: 'scraping' });
    try {
      const res = await farmlistsApi.triggerRaidReportScrape(accountId);
      setScrape({ kind: 'done', scraped: res.scraped });
      reload();
    } catch (err) {
      setScrape({
        kind: 'error',
        message: err instanceof Error ? err.message : 'Scrape failed',
      });
    }
  };

  const onRowToggle = async (targetId: number) => {
    if (expandedTargetId === targetId) {
      setExpandedTargetId(null);
      setExpandedReports([]);
      return;
    }
    setExpandedTargetId(targetId);
    setExpandLoading(true);
    try {
      const all = await farmlistsApi.listTargetReports(accountId, targetId);
      // Show only the last 5 to keep the panel small.
      setExpandedReports(all.slice(0, 5));
    } catch {
      setExpandedReports([]);
    } finally {
      setExpandLoading(false);
    }
  };

  const summary = useMemo(() => {
    if (state.kind !== 'ready') return null;
    const totalRaids = state.metrics.reduce((acc, m) => acc + m.totalRaids, 0);
    const totalLooted = state.metrics.reduce((acc, m) => acc + m.totalResourcesLooted, 0);
    const pruneCount = state.metrics.filter((m) => m.prunCandidate).length;
    return { totalRaids, totalLooted, pruneCount };
  }, [state]);

  const pruneCandidates = useMemo(() => {
    if (state.kind !== 'ready') return [];
    return state.metrics.filter((m) => m.prunCandidate);
  }, [state]);

  if (state.kind === 'loading') return <p className="muted">Loading analytics…</p>;
  if (state.kind === 'error')
    return <p className="form__msg form__msg--error">{state.message}</p>;

  return (
    <div className="stack">
      <div className="settings-savebar">
        <span className="settings-savebar__msg">
          {scrape.kind === 'scraping' && <span className="muted">Scraping reports…</span>}
          {scrape.kind === 'done' && (
            <span className="pill pill--success">
              Scraped {scrape.scraped} new report{scrape.scraped === 1 ? '' : 's'}
            </span>
          )}
          {scrape.kind === 'error' && (
            <span className="form__msg form__msg--error">{scrape.message}</span>
          )}
        </span>
        <button
          type="button"
          className="btn btn--primary"
          onClick={onScrapeNow}
          disabled={scrape.kind === 'scraping'}
        >
          {scrape.kind === 'scraping' ? 'Scraping…' : 'Scrape reports now'}
        </button>
      </div>

      {pruneCandidates.length > 0 && (
        <Card title={`Prune candidates (${pruneCandidates.length})`}>
          <p className="muted">
            These targets have hit 3+ consecutive loss raids and are flagged as RED. Consider
            removing them from the list.
          </p>
          <ul>
            {pruneCandidates.map((m) => (
              <li key={m.farmlistTargetId}>
                ({m.targetX}|{m.targetY}) {m.targetName ?? '—'} — {m.consecutiveLossRaids}{' '}
                consecutive loss raid{m.consecutiveLossRaids === 1 ? '' : 's'}
              </li>
            ))}
          </ul>
          {/* TODO: bulk-remove selected prune candidates — requires a
              FarmlistTarget delete endpoint that doesn't exist yet. */}
          <button type="button" className="btn btn--ghost" disabled title="Bulk-remove not wired up yet">
            Remove selected from list
          </button>
        </Card>
      )}

      <Card title="Summary">
        <ul className="muted" style={{ listStyle: 'none', paddingLeft: 0 }}>
          <li>
            <strong>{summary?.totalRaids ?? 0}</strong> total raids attributed
          </li>
          <li>
            <strong>{(summary?.totalLooted ?? 0).toLocaleString()}</strong> total resources looted
          </li>
          <li>
            <strong>{summary?.pruneCount ?? 0}</strong> prune candidate
            {summary?.pruneCount === 1 ? '' : 's'}
          </li>
        </ul>
      </Card>

      <Card title="Per-target performance">
        {state.metrics.length === 0 ? (
          <p className="muted">No targets on this list yet.</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Light</th>
                <th>Coords</th>
                <th>Name</th>
                <th>Raids</th>
                <th>Looted</th>
                <th>Consec. losses</th>
                <th>Last raid</th>
              </tr>
            </thead>
            <tbody>
              {state.metrics.map((m) => {
                const isOpen = expandedTargetId === m.farmlistTargetId;
                return (
                  <Fragment key={m.farmlistTargetId}>
                    <tr
                      onClick={() => onRowToggle(m.farmlistTargetId)}
                      style={{ cursor: 'pointer' }}
                    >
                      <td>
                        <TrafficDot light={m.trafficLight} />
                      </td>
                      <td>
                        ({m.targetX}|{m.targetY})
                      </td>
                      <td>{m.targetName ?? '—'}</td>
                      <td>{m.totalRaids}</td>
                      <td>{m.totalResourcesLooted.toLocaleString()}</td>
                      <td>{m.consecutiveLossRaids}</td>
                      <td>{m.lastRaidAt ? new Date(m.lastRaidAt).toLocaleString() : '—'}</td>
                    </tr>
                    {isOpen && (
                      <tr>
                        <td colSpan={7}>
                          {expandLoading ? (
                            <span className="muted">Loading reports…</span>
                          ) : expandedReports.length === 0 ? (
                            <span className="muted">No reports yet for this target.</span>
                          ) : (
                            <ul style={{ paddingLeft: '1.2em' }}>
                              {expandedReports.map((r) => (
                                <li key={r.id}>
                                  {new Date(r.raidReturnedAt).toLocaleString()} — {r.outcome} —
                                  looted {(r.woodLooted + r.clayLooted + r.ironLooted + r.cropLooted).toLocaleString()}
                                  {r.ourTroopsLost > 0 ? `, lost ${r.ourTroopsLost} troops` : ''}
                                </li>
                              ))}
                            </ul>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}

/**
 * Small inline traffic-light indicator. Uses the existing pill style with
 * the success/warn/danger colour tokens — no new CSS required.
 */
function TrafficDot({ light }: { light: 'GREEN' | 'YELLOW' | 'RED' }) {
  const cls =
    light === 'GREEN'
      ? 'pill pill--success'
      : light === 'YELLOW'
      ? 'pill pill--warn'
      : 'pill pill--danger';
  return <span className={cls}>{light.charAt(0)}</span>;
}
