import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { useLiveEvent } from '../features/events/EventStreamContext';
import { botLogApi } from '../api/botLog';
import { villagesApi } from '../api/villages';
import {
  BOT_EVENT_OUTCOMES,
  type BotEvent,
  type BotEventOutcome,
  type BotEventPage,
} from '../types/botLog';
import type { Village } from '../types/village';

/**
 * The "what has the bot been doing" screen.
 * <p>
 * Scoped to the currently active account — if the user hasn't picked one,
 * we nudge them to do that first rather than showing a global firehose.
 * <p>
 * Display choices:
 *   - Newest first, paginated. The backend already orders that way and caps
 *     page size; we just pass page/size through.
 *   - Outcome filter ("All / OK / Warn / Error") on top of a "Refresh now"
 *     button. Auto-refresh on a short interval so a user watching the log
 *     after kicking off a run sees events appear — {@code POLL_INTERVAL_MS}
 *     keeps the pressure on the backend low.
 *   - Each row shows time (local clock), outcome chip, action code, a link
 *     to the village if one is set, and the message. The action code uses
 *     {@code <code>} formatting so it looks like a discrete token not prose.
 *   - Village names are resolved by a one-shot fetch on account change; we
 *     keep a simple {id → name} map rather than denormalising into the log
 *     table so village renames flow through without a migration.
 */

const POLL_INTERVAL_MS = 10_000;
const PAGE_SIZE = 50;

export default function BotLogPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [data, setData] = useState<BotEventPage | null>(null);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState<number>(0);
  const [outcome, setOutcome] = useState<BotEventOutcome | 'all'>('all');
  const [villages, setVillages] = useState<Village[]>([]);

  // Free-text search (matches message + action code, case-insensitive).
  // `search` follows the input keystroke-by-keystroke; `searchDebounced` is
  // what actually drives the fetch, so we don't fire a request per keypress.
  const [search, setSearch] = useState('');
  const [searchDebounced, setSearchDebounced] = useState('');
  // Date range, kept in the <input type="datetime-local"> string format
  // ("2026-07-05T14:30", local clock). Converted to ISO instants at fetch time.
  const [fromLocal, setFromLocal] = useState('');
  const [toLocal, setToLocal] = useState('');

  useEffect(() => {
    const handle = window.setTimeout(() => setSearchDebounced(search), 400);
    return () => window.clearTimeout(handle);
  }, [search]);

  // Track the in-flight account id so the response from a stale fetch can't
  // clobber state after the user switches accounts mid-request.
  const accountRef = useRef<number | null>(accountId);
  accountRef.current = accountId;

  // Resolve villageId → name so we can show "Capital" instead of "#42".
  useEffect(() => {
    if (accountId == null) {
      setVillages([]);
      return;
    }
    let cancelled = false;
    villagesApi
      .list(accountId)
      .then((vs) => {
        if (!cancelled && accountRef.current === accountId) setVillages(vs);
      })
      .catch(() => {
        // Village lookup is cosmetic — the log still renders without names.
        if (!cancelled) setVillages([]);
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  const villageById = useMemo(() => {
    const m = new Map<number, Village>();
    for (const v of villages) m.set(v.id, v);
    return m;
  }, [villages]);

  const load = async (nextPage = page) => {
    if (accountId == null) {
      setData(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const resp = await botLogApi.list({
        accountId,
        outcome: outcome === 'all' ? undefined : outcome,
        from: toInstant(fromLocal),
        to: toInstant(toLocal),
        q: searchDebounced || undefined,
        page: nextPage,
        size: PAGE_SIZE,
      });
      if (accountRef.current !== accountId) return; // stale
      setData(resp);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load log');
    } finally {
      setLoading(false);
    }
  };

  // Reset to page 0 whenever a filter or the account changes — a page number
  // from the previous view is likely out of range for the new one.
  useEffect(() => {
    setPage(0);
    void load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, outcome, searchDebounced, fromLocal, toLocal]);

  // Background poll. Only active when the user is on page 0 — deeper pages
  // are historical and wouldn't benefit from polling, and polling them would
  // reshuffle rows under the user as new events arrive.
  useEffect(() => {
    if (accountId == null || page !== 0) return;
    const handle = window.setInterval(() => {
      void load(0);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accountId, page, outcome, searchDebounced, fromLocal, toLocal]);

  // Live push (NOT4): when the backend records a bot-event for the account
  // we're viewing and we're on the newest page, refresh — coalescing bursts
  // (a "scrape all" logs many events at once) into one fetch per 400ms. The
  // 10s poll above stays as a fallback if the stream drops.
  const liveRefreshTimer = useRef<number | null>(null);
  useLiveEvent('bot-event', (data) => {
    const evt = data as { accountId?: number } | null;
    if (accountId == null || page !== 0 || evt?.accountId !== accountId) return;
    if (liveRefreshTimer.current != null) return;
    liveRefreshTimer.current = window.setTimeout(() => {
      liveRefreshTimer.current = null;
      void load(0);
    }, 400);
  });

  const onPrev = () => {
    if (page <= 0) return;
    const next = page - 1;
    setPage(next);
    void load(next);
  };

  const onNext = () => {
    if (!data) return;
    if (page + 1 >= data.totalPages) return;
    const next = page + 1;
    setPage(next);
    void load(next);
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Log"
        subtitle="Bot aktivnost: scrape, login, send, build."
      />
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to see its log.
          </p>
        </Card>
      )}

      {accountId != null && (
        <Card title="Activity">
          <div className="toolbar" style={{ flexWrap: 'wrap', rowGap: '0.5rem' }}>
            <label className="toolbar__label">Outcome</label>
            <select
              value={outcome}
              onChange={(e) => setOutcome(e.target.value as BotEventOutcome | 'all')}
            >
              <option value="all">All</option>
              {BOT_EVENT_OUTCOMES.map((o) => (
                <option key={o} value={o}>{o}</option>
              ))}
            </select>
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search message or action…"
              aria-label="Search log messages and action codes"
              style={{ minWidth: '14rem' }}
            />
            <label className="toolbar__label">From</label>
            <input
              type="datetime-local"
              value={fromLocal}
              onChange={(e) => setFromLocal(e.target.value)}
              aria-label="Only events after"
            />
            <label className="toolbar__label">To</label>
            <input
              type="datetime-local"
              value={toLocal}
              onChange={(e) => setToLocal(e.target.value)}
              aria-label="Only events before"
            />
            {(search !== '' || fromLocal !== '' || toLocal !== '' || outcome !== 'all') && (
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => {
                  setSearch('');
                  setFromLocal('');
                  setToLocal('');
                  setOutcome('all');
                }}
              >
                Clear filters
              </button>
            )}
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => void load(page)}
              disabled={loading}
              title="Fetch the latest events now (auto-refreshes every 10s on page 1)."
            >
              {loading ? 'Refreshing…' : 'Refresh'}
            </button>
            {data && (
              <span className="muted" style={{ marginLeft: 'auto' }}>
                {data.total} event{data.total === 1 ? '' : 's'}
              </span>
            )}
          </div>

          {error && <p className="form__msg form__msg--error">{error}</p>}

          {data && data.items.length === 0 && !loading && (
            <p className="muted">No events recorded yet — the log fills up as the bot runs.</p>
          )}

          {data && data.items.length > 0 && (
            <>
              <table className="table" style={{ tableLayout: 'fixed' }}>
                <thead>
                  <tr>
                    <th style={{ width: '11rem' }}>When</th>
                    <th style={{ width: '5rem' }}>Outcome</th>
                    <th style={{ width: '11rem' }}>Action</th>
                    <th style={{ width: '10rem' }}>Village</th>
                    <th>Message</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((ev) => (
                    <BotEventRow key={ev.id} event={ev} villageById={villageById} />
                  ))}
                </tbody>
              </table>

              <div
                style={{
                  display: 'flex',
                  gap: '0.5rem',
                  alignItems: 'center',
                  marginTop: '0.75rem',
                }}
              >
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={onPrev}
                  disabled={page <= 0 || loading}
                >
                  ← Newer
                </button>
                <span className="muted">
                  Page {data.page + 1} of {Math.max(1, data.totalPages)}
                </span>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={onNext}
                  disabled={!data || page + 1 >= data.totalPages || loading}
                >
                  Older →
                </button>
              </div>
            </>
          )}
        </Card>
      )}
    </div>
  );
}

/**
 * Convert a <input type="datetime-local"> value ("2026-07-05T14:30", local
 * clock, possibly half-typed) to an ISO-8601 UTC instant for the API.
 * Returns undefined for empty or not-yet-valid input so a partially typed
 * date doesn't produce a garbage filter.
 */
function toInstant(local: string): string | undefined {
  if (local === '') return undefined;
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}

function BotEventRow({
  event,
  villageById,
}: {
  event: BotEvent;
  villageById: Map<number, Village>;
}) {
  const village = event.villageId != null ? villageById.get(event.villageId) ?? null : null;
  return (
    <tr>
      <td className="muted">{new Date(event.createdAt).toLocaleString()}</td>
      <td>
        <span className={`pill pill--${outcomePill(event.outcome)}`}>{event.outcome}</span>
      </td>
      <td>
        <code className="code-chip">{event.action}</code>
      </td>
      <td>
        {village ? (
          <Link to={`/villages/${village.id}`} className="link">
            {village.name}
          </Link>
        ) : event.villageId != null ? (
          <span className="muted">#{event.villageId}</span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>{event.message ?? <span className="muted">—</span>}</td>
    </tr>
  );
}

/**
 * Maps our three outcome values to the generic {@code pill--*} classes the
 * rest of the app already uses. Keeping this in one place so adding a fourth
 * outcome later is a single-line change.
 */
function outcomePill(outcome: BotEventOutcome): 'success' | 'warn' | 'danger' {
  if (outcome === 'OK') return 'success';
  if (outcome === 'WARN') return 'warn';
  return 'danger';
}
