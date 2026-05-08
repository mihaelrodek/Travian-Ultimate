import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useCurrentAccount } from '../../hooks/useCurrentAccount';
import { villagesApi } from '../../api/villages';
import { accountsApi } from '../../api/accounts';
import type { Account } from '../../types/account';
import type { BuildingQueueItem, Village } from '../../types/village';
import { formatFinishLabel } from '../../utils/time';

/**
 * Always-visible footer strip summarising the build queue across the current
 * account's villages. The bar is pinned to the bottom of the content column
 * (see {@code .floating-queue-bar} in index.css) and appears on every page
 * so the player can glance at "what is being built right now" without
 * navigating to the villages view.
 * <p>
 * Scope toggle — "All villages" vs "Current village":
 *   - "Current village" is only meaningful when the URL is {@code /villages/:id}.
 *     The bar parses {@code useLocation()} to find that id; outside that route
 *     the "Current" mode falls back gracefully to a hint rather than showing
 *     nothing (the user hasn't picked a village to narrow to).
 *   - The scope choice is persisted to localStorage so reloading the app
 *     doesn't reset it.
 * <p>
 * Data source: the existing {@code GET /villages?accountId=…} already returns
 * each village's full queue, so this component polls that endpoint on an
 * interval and derives per-village status locally. That means no backend
 * change is needed to ship the bar, and the queue stays in sync with any
 * mutation the rest of the UI makes.
 */
const POLL_INTERVAL_MS = 8000;
const LS_SCOPE_KEY = 'queueBar.scope';
/**
 * Per-account "last village I looked at" pointer. Accessed via the shared key
 * prefix {@code scope.lastVillageId.<accountId>}. VillagesListPage writes to it
 * on every selection; FloatingQueueBar reads it when the user is NOT on the
 * Villages route so "Current village" still has something to narrow to.
 */
const LS_LAST_VILLAGE_PREFIX = 'scope.lastVillageId.';

type Scope = 'ALL' | 'CURRENT';

function readStoredScope(): Scope {
  try {
    const raw = localStorage.getItem(LS_SCOPE_KEY);
    return raw === 'CURRENT' ? 'CURRENT' : 'ALL';
  } catch {
    return 'ALL';
  }
}

function writeStoredScope(scope: Scope): void {
  try {
    localStorage.setItem(LS_SCOPE_KEY, scope);
  } catch {
    /* private mode — nothing to do */
  }
}

function readStoredLastVillage(accountId: number): number | null {
  try {
    const raw = localStorage.getItem(LS_LAST_VILLAGE_PREFIX + accountId);
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

/**
 * Parse {@code /villages/:id} out of a pathname so we can answer "which
 * village is currently selected" without plumbing a global VillageContext.
 * Returns null for every other route.
 */
function currentVillageIdFromPath(pathname: string): number | null {
  const match = pathname.match(/^\/villages\/(\d+)/);
  if (!match) return null;
  const n = Number(match[1]);
  return Number.isFinite(n) ? n : null;
}

/**
 * Route prefixes where the queue bar is deliberately hidden. The accounts
 * list + the single-account detail page are session-management screens —
 * the queue is about in-game progress, so showing it there is noise. Keep
 * this list narrow: other pages benefit from the always-visible queue
 * summary, so don't expand without cause.
 */
const HIDDEN_ROUTE_PREFIXES = ['/accounts'];

function isHiddenRoute(pathname: string): boolean {
  return HIDDEN_ROUTE_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(prefix + '/'),
  );
}

export default function FloatingQueueBar() {
  const { accountId } = useCurrentAccount();
  const location = useLocation();
  const hidden = isHiddenRoute(location.pathname);
  const [scope, setScopeState] = useState<Scope>(() => readStoredScope());
  const [villages, setVillages] = useState<Village[]>([]);
  const [account, setAccount] = useState<Account | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  // Track the in-flight account id so a stale response can't clobber state
  // after the user switches accounts mid-fetch.
  const accountIdRef = useRef<number | null>(accountId);
  accountIdRef.current = accountId;

  const setScope = (next: Scope) => {
    setScopeState(next);
    writeStoredScope(next);
  };

  const currentVillageId = useMemo(
    () => currentVillageIdFromPath(location.pathname),
    [location.pathname],
  );

  /**
   * "Which village does CURRENT scope target?" — the bar resolves this in a
   * three-step fallback so it always has something to show:
   *   1. The {@code /villages/:id} in the URL, if the user is actively
   *      browsing a village right now.
   *   2. The per-account localStorage pointer written by VillagesListPage on
   *      selection — so we remember the last village across routes.
   *   3. The first real village in the list — so even on a brand-new session
   *      the bar isn't empty when CURRENT is the chosen scope.
   * <p>
   * Computed inside the {@code shown} memo below because step 3 needs the
   * realVillages array; factoring this out into its own hook would force an
   * extra dependency cycle with no clarity win.
   */

  // Pull the current account once per switch so we can surface Plus status in
  // the bar header. Cheap — accounts don't change while the bar is mounted and
  // we don't need high-frequency polling for this. The scrape loop updates
  // the plus flag on the server; the next village-list poll will fan out a
  // stale chip for a few seconds at most, which is fine.
  useEffect(() => {
    if (accountId == null) {
      setAccount(null);
      return;
    }
    let cancelled = false;
    accountsApi
      .get(accountId)
      .then((a) => {
        if (!cancelled && accountIdRef.current === accountId) setAccount(a);
      })
      .catch(() => {
        // Silently ignore — the plus chip just won't render. The village
        // poller below will surface any real connectivity issue.
        if (!cancelled) setAccount(null);
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  // Poll the villages list for the active account. Account changes reset
  // the local state so the bar doesn't briefly show the previous account's
  // queue while the new fetch is in flight.
  useEffect(() => {
    if (accountId == null) {
      setVillages([]);
      setError(null);
      return;
    }
    let cancelled = false;
    const fetchOnce = async () => {
      setLoading(true);
      try {
        const data = await villagesApi.list(accountId);
        if (cancelled || accountIdRef.current !== accountId) return;
        setVillages(data);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'Failed to load queue');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void fetchOnce();
    const handle = window.setInterval(fetchOnce, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(handle);
    };
  }, [accountId]);

  // Filter out the (0|0) sentinel villages that the scraper can emit when
  // header parsing fails. They're not real villages and would just confuse
  // the bar; the backend has been hardened against creating new ones, but
  // this is a cheap second line of defense for the ones already in the DB.
  const realVillages = useMemo(
    () => villages.filter((v) => !(v.coordinateX === 0 && v.coordinateY === 0)),
    [villages],
  );

  const shown = useMemo<Village[]>(() => {
    if (scope === 'CURRENT') {
      // URL first — it's the strongest signal of "the village I care about".
      let targetId = currentVillageId;
      // localStorage pointer — remember what the user last opened on the
      // Villages page so the bar survives navigating away from /villages/:id.
      if (targetId == null && accountId != null) {
        targetId = readStoredLastVillage(accountId);
      }
      // First village in the canonical order — so CURRENT is never empty on
      // a fresh session. realVillages is already sorted by orderIndex server-side.
      if (targetId == null) {
        targetId = realVillages[0]?.id ?? null;
      }
      if (targetId == null) return [];
      const match = realVillages.find((v) => v.id === targetId);
      return match ? [match] : [];
    }
    // Show every village that has at least one pending/running row first,
    // idle villages collapsed to the tail so the bar leads with action.
    const withQueue = realVillages.filter((v) => hasActiveQueue(v));
    const idle = realVillages.filter((v) => !hasActiveQueue(v));
    return [...withQueue, ...idle];
  }, [realVillages, scope, currentVillageId, accountId]);

  // Suppress the bar on the accounts screens — they're about session
  // management, not in-game progress, and rendering the bar there is both
  // noisy and pushes the page content up. Done after the hooks so React's
  // hook order stays stable across renders.
  if (hidden) {
    return null;
  }

  if (accountId == null) {
    // Nothing to show without a scope. Keep the bar in the layout so
    // positioning is stable but render an empty hint.
    return (
      <div className="floating-queue-bar floating-queue-bar--empty">
        <span className="muted">Pick an account to see the build queue.</span>
      </div>
    );
  }

  const totalCount = realVillages.reduce((acc, v) => acc + (v.queue?.length ?? 0), 0);
  const runningCount = realVillages.reduce(
    (acc, v) => acc + (v.queue?.filter((q) => q.status === 'RUNNING').length ?? 0),
    0,
  );

  return (
    <div className="floating-queue-bar" aria-label="Build queue">
      <div className="floating-queue-bar__header">
        <strong className="floating-queue-bar__title">Queue</strong>
        <span className="floating-queue-bar__summary muted">
          {runningCount} running · {totalCount} queued
        </span>
        {account && (
          <span
            className={`chip ${account.plusAccountActive ? 'chip--plus' : 'chip--plus-off'}`}
            title={
              account.plusAccountActive
                ? 'Travian Plus active — extra concurrent build slot granted.'
                : 'Travian Plus not active — one build slot only (non-Romans), or 1B+1F (Romans).'
            }
          >
            Plus: {account.plusAccountActive ? 'Active' : 'Off'}
          </span>
        )}
        <div className="floating-queue-bar__scope" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={scope === 'ALL'}
            className={`floating-queue-bar__scope-btn${scope === 'ALL' ? ' floating-queue-bar__scope-btn--active' : ''}`}
            onClick={() => setScope('ALL')}
            title="Show queues across every village on this account"
          >
            All villages
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={scope === 'CURRENT'}
            className={`floating-queue-bar__scope-btn${scope === 'CURRENT' ? ' floating-queue-bar__scope-btn--active' : ''}`}
            onClick={() => setScope('CURRENT')}
            title="Show the queue of the village open on the Villages page"
          >
            Current village
          </button>
        </div>
        {loading && <span className="muted floating-queue-bar__loading">…</span>}
        {error && <span className="form__msg form__msg--error">{error}</span>}
      </div>

      <div className="floating-queue-bar__rows">
        {shown.length === 0 ? (
          <span className="muted">No villages yet.</span>
        ) : (
          shown.map((v) => (
            <VillageQueueRow
              key={v.id}
              village={v}
              serverUtcOffsetMinutes={account?.serverUtcOffsetMinutes ?? null}
            />
          ))
        )}
      </div>
    </div>
  );
}

function hasActiveQueue(v: Village): boolean {
  return (v.queue ?? []).some((q) => q.status === 'PENDING' || q.status === 'RUNNING');
}

/**
 * One village's row. Shows the currently building item (RUNNING) or, if
 * nothing is running, the first PENDING as "next". Extra pending rows show
 * as a +N count so the bar stays horizontal and scannable.
 * <p>
 * When the running item has a scraped {@code finishesAt}, the row also shows
 * a server-clock "done at HH:MM · in Mm" hint so the player doesn't have to
 * open the village to see when the slot frees up.
 */
function VillageQueueRow({
  village,
  serverUtcOffsetMinutes,
}: {
  village: Village;
  serverUtcOffsetMinutes: number | null;
}) {
  const queue = village.queue ?? [];
  const running = queue.find((q) => q.status === 'RUNNING');
  const pending = queue.filter((q) => q.status === 'PENDING');
  const extraPendingCount = Math.max(0, pending.length - 1);
  const headline = running ?? pending[0] ?? null;
  const finishLabel = running
    ? formatFinishLabel(running.finishesAt, serverUtcOffsetMinutes)
    : null;

  return (
    <Link className="floating-queue-bar__row" to={`/villages/${village.id}`}>
      <span className="floating-queue-bar__village">
        {village.name}
        <span className="muted floating-queue-bar__coords">
          ({village.coordinateX}|{village.coordinateY})
        </span>
      </span>
      {headline ? (
        <span className={`floating-queue-bar__item floating-queue-bar__item--${running ? 'running' : 'pending'}`}>
          <span className="floating-queue-bar__item-label">
            {running ? 'Building' : 'Next'}:
          </span>{' '}
          {formatQueueItem(headline)}
          {finishLabel && (
            <span className="muted floating-queue-bar__finish"> · {finishLabel}</span>
          )}
          {extraPendingCount > 0 && (
            <span className="muted floating-queue-bar__extra">+{extraPendingCount} queued</span>
          )}
        </span>
      ) : (
        <span className="muted floating-queue-bar__idle">idle</span>
      )}
    </Link>
  );
}

function formatQueueItem(it: BuildingQueueItem): string {
  const name = humanize(it.buildingType);
  return `${name} → Lvl ${it.targetLevel}`;
}

function humanize(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}
