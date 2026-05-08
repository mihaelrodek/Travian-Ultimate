import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { statisticsApi } from '../api/statistics';
import type { HistoryResponse, StatCategory, Top10Response } from '../types/statistics';
import { STAT_CATEGORIES } from '../types/statistics';
import HistoryChart from '../features/statistics/HistoryChart';

/**
 * Statistics page — four-up dashboard showing PvP / PvE / Defense / Raiders
 * history charts side by side, with a click-to-expand detail panel for any
 * one category.
 * <p>
 * Layout:
 *   - top: hours range selector + capture/refresh + last-captured indicator
 *   - middle: 2x2 grid of mini history charts, one per {@link StatCategory}.
 *     Each tile is a button that opens the detail modal for that category.
 *   - modal: full-size chart + the per-player top-10 table for the chosen
 *     category. Closes on Esc, backdrop click, or the X button.
 * <p>
 * The page sends a heartbeat every 2 minutes while mounted so the hourly
 * capture scheduler knows this account is actively being watched.
 */

const HEARTBEAT_MS = 2 * 60 * 1000;
const HISTORY_RANGES = [1, 2, 6, 12, 24, 48, 72] as const;
type HistoryRange = (typeof HISTORY_RANGES)[number];

type Top10Map = Record<StatCategory, Top10Response | null>;
type HistoryMap = Record<StatCategory, HistoryResponse | null>;

const EMPTY_TOP10: Top10Map = { PVP: null, PVE: null, DEFENSE: null, RAIDERS: null };
const EMPTY_HISTORY: HistoryMap = { PVP: null, PVE: null, DEFENSE: null, RAIDERS: null };

export default function StatisticsPage() {
  const { t } = useTranslation();
  const { accountId } = useCurrentAccount();

  const [top10, setTop10] = useState<Top10Map>(EMPTY_TOP10);
  const [history, setHistory] = useState<HistoryMap>(EMPTY_HISTORY);
  const [hours, setHours] = useState<HistoryRange>(12);

  const [loading, setLoading] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Which chart is currently expanded into the detail modal. Null = no
  // modal. Set on chart click; cleared by backdrop click / X button / Esc.
  const [expanded, setExpanded] = useState<StatCategory | null>(null);

  /**
   * Fetch all four categories' top-10 + history in parallel. Settle errors
   * per category so a single failed fetch (e.g. RAIDERS not yet captured)
   * doesn't blank the rest of the dashboard.
   */
  const reloadAll = useCallback(async () => {
    if (accountId == null) return;
    setLoading(true);
    try {
      const top10Settled = await Promise.allSettled(
        STAT_CATEGORIES.map((c) => statisticsApi.top10(accountId, c)),
      );
      const histSettled = await Promise.allSettled(
        STAT_CATEGORIES.map((c) => statisticsApi.history(accountId, c, hours)),
      );
      const nextTop10: Top10Map = { ...EMPTY_TOP10 };
      const nextHist: HistoryMap = { ...EMPTY_HISTORY };
      STAT_CATEGORIES.forEach((c, i) => {
        const t10 = top10Settled[i];
        if (t10.status === 'fulfilled') nextTop10[c] = t10.value;
        const h = histSettled[i];
        if (h.status === 'fulfilled') nextHist[c] = h.value;
      });
      setTop10(nextTop10);
      setHistory(nextHist);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('statistics.errors.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [accountId, hours, t]);

  useEffect(() => {
    if (accountId == null) {
      setTop10(EMPTY_TOP10);
      setHistory(EMPTY_HISTORY);
      return;
    }
    void reloadAll();
  }, [accountId, hours, reloadAll]);

  // Heartbeat every 2 min so the backend scheduler keeps capturing for us.
  const heartbeatTimer = useRef<number | null>(null);
  useEffect(() => {
    if (accountId == null) return;
    const id = accountId;
    const ping = () => {
      statisticsApi.heartbeat(id).catch(() => {
        /* best-effort */
      });
    };
    ping();
    heartbeatTimer.current = window.setInterval(ping, HEARTBEAT_MS);
    return () => {
      if (heartbeatTimer.current != null) {
        window.clearInterval(heartbeatTimer.current);
        heartbeatTimer.current = null;
      }
    };
  }, [accountId]);

  // Esc closes the detail modal — same shape as a typical lightbox.
  useEffect(() => {
    if (expanded == null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setExpanded(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [expanded]);

  const captureNow = async () => {
    if (accountId == null) return;
    setCapturing(true);
    setError(null);
    try {
      const snapshotByCategory = await statisticsApi.capture(accountId);
      setTop10((prev) => ({ ...prev, ...snapshotByCategory }));
      // Refresh history too — capture writes a new snapshot which becomes
      // the latest history point.
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('statistics.errors.captureFailed'));
    } finally {
      setCapturing(false);
    }
  };

  // Latest captured-at across the four categories — used as the toolbar
  // "Latest:" line. They should all share a timestamp because capture
  // writes them in one shot, but pick max() defensively.
  const latestCapturedAt = useMemo(() => {
    let max: number | null = null;
    for (const c of STAT_CATEGORIES) {
      const t10 = top10[c];
      if (!t10?.capturedAt) continue;
      const ms = Date.parse(t10.capturedAt);
      if (!Number.isNaN(ms) && (max == null || ms > max)) max = ms;
    }
    return max;
  }, [top10]);

  if (accountId == null) {
    return (
      <div className="stack stack--xwide">
        <Card>
          <p className="muted">{t('statistics.pickAccount')}</p>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack stack--xwide">
      <Card>
        <div className="toolbar" style={{ gap: '0.75rem', flexWrap: 'wrap' }}>
          <label className="muted" htmlFor="stats-range">{t('statistics.range')}</label>
          <select
            id="stats-range"
            value={hours}
            onChange={(e) => setHours(Number(e.target.value) as HistoryRange)}
          >
            {HISTORY_RANGES.map((h) => (
              <option key={h} value={h}>
                {h === 1 ? t('statistics.hourOne') : t('statistics.hoursN', { n: h })}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn--primary btn--sm"
            onClick={captureNow}
            disabled={capturing}
          >
            {capturing ? t('statistics.capturing') : t('statistics.captureNow')}
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => void reloadAll()}
            disabled={loading}
          >
            {loading ? t('common.loading') : t('common.refresh')}
          </button>
          <span className="muted" style={{ flex: 1, textAlign: 'right' }}>
            {latestCapturedAt
              ? t('statistics.latest', { when: new Date(latestCapturedAt).toLocaleString() })
              : t('statistics.noCapturesYet')}
          </span>
        </div>
        {error && <p className="form__msg form__msg--error" style={{ marginTop: '0.5rem' }}>{error}</p>}
      </Card>

      {/*
        2x2 dashboard. Render the four tiles in fixed top-left → bottom-right
        order so the user always finds the same category in the same corner:
        PvP top-left, PvE top-right, Defense bottom-left, Raiders bottom-right.
      */}
      <div className="stats-grid">
        {STAT_CATEGORIES.map((c) => (
          <StatTile
            key={c}
            category={c}
            top10={top10[c]}
            history={history[c]}
            label={t(`statistics.category.${c.toLowerCase()}.title`)}
            scoreLabel={t(`statistics.category.${c.toLowerCase()}.score`)}
            emptyText={t('statistics.chartEmpty')}
            onClick={() => setExpanded(c)}
          />
        ))}
      </div>

      {expanded && (
        <DetailOverlay
          category={expanded}
          top10={top10[expanded]}
          history={history[expanded]}
          label={t(`statistics.category.${expanded.toLowerCase()}.title`)}
          scoreLabel={t(`statistics.category.${expanded.toLowerCase()}.score`)}
          emptyText={t('statistics.chartEmpty')}
          playerColumnLabel={t('statistics.columns.player')}
          onClose={() => setExpanded(null)}
        />
      )}
    </div>
  );
}

interface StatTileProps {
  category: StatCategory;
  top10: Top10Response | null;
  history: HistoryResponse | null;
  label: string;
  scoreLabel: string;
  emptyText: string;
  onClick: () => void;
}

/**
 * One quadrant of the dashboard — title, mini chart, click-to-expand. The
 * tile is a real button so keyboard + screen-reader users can open the
 * detail view; the chart inside is decorative for that purpose.
 */
function StatTile({
  category,
  top10,
  history,
  label,
  scoreLabel,
  emptyText,
  onClick,
}: StatTileProps) {
  const points = history?.points ?? [];
  return (
    <button
      type="button"
      className={`stats-tile stats-tile--${category.toLowerCase()}`}
      onClick={onClick}
      title={`Open ${label} details`}
    >
      <div className="stats-tile__header">
        <span className="stats-tile__title">{label}</span>
        <span className="stats-tile__hint muted">{scoreLabel}</span>
      </div>
      <div className="stats-tile__chart">
        <HistoryChart points={points} yLabel={scoreLabel} emptyText={emptyText} />
      </div>
      {top10 && top10.rows.length > 0 && (
        <div className="stats-tile__leader muted">
          #1 {top10.rows[0].playerName}
          {top10.rows[0].score != null && (
            <> · {top10.rows[0].score.toLocaleString()}</>
          )}
        </div>
      )}
    </button>
  );
}

interface DetailOverlayProps {
  category: StatCategory;
  top10: Top10Response | null;
  history: HistoryResponse | null;
  label: string;
  scoreLabel: string;
  emptyText: string;
  playerColumnLabel: string;
  onClose: () => void;
}

/**
 * Modal overlay for the click-to-detail view. Renders the full-size chart
 * and the top-10 table for one category. Closes on backdrop click; the
 * inner panel stops the click event so users can interact with the table
 * without dismissing the modal.
 */
function DetailOverlay({
  category,
  top10,
  history,
  label,
  scoreLabel,
  emptyText,
  playerColumnLabel,
  onClose,
}: DetailOverlayProps) {
  return (
    <div
      className="stats-overlay"
      role="dialog"
      aria-modal="true"
      aria-label={`${label} details`}
      onClick={onClose}
    >
      <div className="stats-overlay__panel" onClick={(e) => e.stopPropagation()}>
        <div className="stats-overlay__header">
          <h2 className="stats-overlay__title">
            {label} <span className="muted" style={{ fontSize: '0.85rem' }}>· {scoreLabel}</span>
          </h2>
          <button
            type="button"
            className="stats-overlay__close"
            onClick={onClose}
            aria-label="Close"
            title="Close (Esc)"
          >
            ✕
          </button>
        </div>

        <HistoryChart
          points={history?.points ?? []}
          yLabel={scoreLabel}
          emptyText={emptyText}
        />

        <div className="stats-overlay__table-wrap">
          {top10 == null || top10.rows.length === 0 ? (
            <p className="muted">No top-10 data yet for {label}.</p>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '3.5rem' }}>#</th>
                  <th>{playerColumnLabel}</th>
                  <th style={{ textAlign: 'right' }}>{scoreLabel}</th>
                </tr>
              </thead>
              <tbody>
                {top10.rows.map((row) => (
                  <tr key={`${category}-${row.rank}-${row.playerName}`}>
                    <td>
                      {row.rank}
                      <DeltaBadge value={row.rankDelta} />
                    </td>
                    <td>{row.playerName}</td>
                    <NumCell value={row.score} delta={row.scoreDelta} />
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}

function NumCell({ value, delta }: { value: number | null; delta: number | null }) {
  return (
    <td style={{ textAlign: 'right' }}>
      {value == null ? '—' : value.toLocaleString()}
      <DeltaBadge value={delta} />
    </td>
  );
}

function DeltaBadge({ value }: { value: number | null }) {
  if (value == null) return null;
  if (value === 0) {
    return <span className="delta delta--flat">·</span>;
  }
  const cls = value > 0 ? 'delta delta--up' : 'delta delta--down';
  const sign = value > 0 ? '+' : '';
  return (
    <span className={cls}>
      {sign}
      {value.toLocaleString()}
    </span>
  );
}
