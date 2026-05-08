import { useEffect, useMemo, useState, type DragEvent as ReactDragEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import Card from '../components/Card';
import VillageDetailPanel from '../features/villages/VillageDetailPanel';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { villagesApi } from '../api/villages';
import { ApiException } from '../types/api';
import type { Village } from '../types/village';

/**
 * "Reload all villages" UI state. Mirrors the small state machine the button
 * used to live in on the settings page — idle → running → done/error. Error
 * distinguishes the 409-not-logged-in case because the fix is specific
 * (start a login first) rather than generic.
 */
export type ReloadAllState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'done'; count: number }
  | { kind: 'error'; message: string };

/**
 * Master-detail villages page.
 * <p>
 * Left panel is a flat list of every village on the active account (now
 * collapsible — see {@code villagesCollapsed}); right panel is the selected
 * village's detail. Clicking a village updates the URL to
 * {@code /villages/:id} so deep links and browser-back work, but no page
 * transition happens — it's the same component handling both
 * {@code /villages} and {@code /villages/:id}.
 * <p>
 * The list is sorted by {@link Village#orderIndex} — a user-chosen order
 * persisted on the backend (see {@code VillageService.reorder}). The HTML5
 * drag/drop handlers below let the user rearrange rows; on drop we fire
 * {@code POST /villages/reorder} and re-hydrate from the server response so
 * the optimistic order stays in sync with what Hibernate wrote.
 * <p>
 * The "Reload all villages" action and the collapse toggle for the village
 * list both live <em>inside</em> the right pane's tab toolbar (rendered by
 * {@link VillageDetailPanel}). The tabs row is the single horizontal toolbar
 * on this page — keeps a stack of three controls from spanning three rows.
 * <p>
 * Two more ergonomic bits:
 *   - When the user lands on {@code /villages} with nothing selected we
 *     auto-navigate (replace) to the first village so the detail pane is
 *     never empty on load.
 *   - Every selection writes {@code scope.lastVillageId.<accountId>} to
 *     localStorage. {@link FloatingQueueBar} reads that key when the URL
 *     isn't {@code /villages/:id} so its CURRENT scope always has something
 *     to show.
 */

const LS_LAST_VILLAGE_PREFIX = 'scope.lastVillageId.';
const LS_VILLAGES_COLLAPSED = 'villages.listCollapsed';

function writeStoredLastVillage(accountId: number, villageId: number): void {
  try {
    localStorage.setItem(LS_LAST_VILLAGE_PREFIX + accountId, String(villageId));
  } catch {
    /* private mode — nothing to do */
  }
}

/**
 * Pure reorder helper — moves {@code sourceId} to sit at the position
 * currently held by {@code targetId}. When source is above target the moved
 * item slots in after; when source is below target it slots in before. This
 * is the standard "drop on top of a row" semantics most users expect.
 */
function moveIdToBefore(list: number[], sourceId: number, targetId: number): number[] {
  const src = list.indexOf(sourceId);
  const tgt = list.indexOf(targetId);
  if (src < 0 || tgt < 0 || src === tgt) return list;
  const copy = [...list];
  const [moved] = copy.splice(src, 1);
  const insertAt = copy.indexOf(targetId);
  copy.splice(insertAt, 0, moved);
  return copy;
}

export default function VillagesListPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();
  const { id: routeId } = useParams<{ id?: string }>();
  const navigate = useNavigate();

  const [villages, setVillages] = useState<Village[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);

  // Drag state — the id being dragged and the id currently hovered over as a
  // drop target. Tracking hovered-over separately lets us render a drop-
  // indicator class without rebuilding the list on every dragover event.
  const [draggingId, setDraggingId] = useState<number | null>(null);
  const [dragOverId, setDragOverId] = useState<number | null>(null);
  const [reorderError, setReorderError] = useState<string | null>(null);

  // Reload-all status (button itself is rendered by VillageDetailPanel — this
  // page owns the state because the reload affects every village in the list,
  // not just the currently-selected one).
  const [reloadState, setReloadState] = useState<ReloadAllState>({ kind: 'idle' });

  // Villages list collapsed state — persisted in localStorage so coming back
  // to the page keeps the same width preference. Defaults to expanded.
  const [villagesCollapsed, setVillagesCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(LS_VILLAGES_COLLAPSED) === '1';
    } catch {
      return false;
    }
  });
  useEffect(() => {
    try {
      localStorage.setItem(LS_VILLAGES_COLLAPSED, villagesCollapsed ? '1' : '0');
    } catch {
      /* private mode — nothing to do */
    }
  }, [villagesCollapsed]);

  const selectedId = routeId ? Number(routeId) : null;

  useEffect(() => {
    if (accountId == null) {
      setVillages([]);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    villagesApi.list(accountId)
      .then((data) => {
        if (cancelled) return;
        setVillages(data);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'Failed to load villages');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [accountId]);

  /**
   * If the URL points at a village that isn't in this account's list (e.g. the
   * user switched accounts while on a deep link), clear the selection so the
   * right pane doesn't try to load a village the scope can't see.
   */
  useEffect(() => {
    if (selectedId == null) return;
    if (loading) return;
    if (villages.length === 0) return;
    if (!villages.some((v) => v.id === selectedId)) {
      navigate('/villages', { replace: true });
    }
  }, [selectedId, villages, loading, navigate]);

  /**
   * Sort by the user-chosen order with createdAt as a deterministic tiebreaker.
   * The backend orders the same way, so this really just protects against
   * optimistic local state that hasn't been re-hydrated yet.
   */
  const sorted = useMemo(() => {
    const copy = [...villages];
    copy.sort((a, b) => {
      if (a.orderIndex !== b.orderIndex) return a.orderIndex - b.orderIndex;
      return a.createdAt.localeCompare(b.createdAt);
    });
    return copy;
  }, [villages]);

  /**
   * Auto-navigate to the first village whenever landing on plain
   * {@code /villages} with at least one village available. The replace flag
   * keeps browser-back sane — the user still returns to whatever page they
   * came from, not to an empty /villages.
   */
  useEffect(() => {
    if (accountId == null) return;
    if (loading) return;
    if (selectedId != null) return;
    if (sorted.length === 0) return;
    navigate(`/villages/${sorted[0].id}`, { replace: true });
  }, [accountId, loading, selectedId, sorted, navigate]);

  /**
   * Mirror every selection to localStorage so FloatingQueueBar's CURRENT
   * scope can resolve "last opened village" when the user has navigated
   * away from /villages/:id. Per-account keyed — switching accounts doesn't
   * leak the pointer across scopes.
   */
  useEffect(() => {
    if (accountId == null) return;
    if (selectedId == null) return;
    writeStoredLastVillage(accountId, selectedId);
  }, [accountId, selectedId]);

  const selectVillage = (id: number) => {
    navigate(`/villages/${id}`);
  };

  /**
   * Trigger a full re-scrape of every village on the active account. Requires
   * the account to be logged in — 409 is caught and surfaced as a one-line
   * hint rather than leaving the user to decode the generic error.
   */
  const reloadAllVillages = async () => {
    if (accountId == null) return;
    setReloadState({ kind: 'running' });
    try {
      const res = await villagesApi.scrapeAllForAccount(accountId);
      setReloadState({ kind: 'done', count: res.villagesScraped });
      // Re-hydrate the sidebar so freshly-scraped population/queue/etc. show up
      // without requiring the user to click away and back.
      try {
        const fresh = await villagesApi.list(accountId);
        setVillages(fresh);
      } catch {
        /* list refresh is best-effort; the scrape itself already succeeded */
      }
    } catch (err) {
      if (err instanceof ApiException && err.status === 409) {
        setReloadState({
          kind: 'error',
          message: 'This account is not logged in. Start a login from the account page first.',
        });
      } else {
        setReloadState({
          kind: 'error',
          message: err instanceof Error ? err.message : 'Reload failed',
        });
      }
    }
  };

  // --- Drag/drop handlers ---------------------------------------------------

  const handleDragStart = (id: number) => (e: ReactDragEvent<HTMLButtonElement>) => {
    setDraggingId(id);
    setReorderError(null);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(id));
  };

  const handleDragOver = (id: number) => (e: ReactDragEvent<HTMLLIElement>) => {
    // preventDefault is mandatory to mark this element as a valid drop target.
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragOverId !== id) setDragOverId(id);
  };

  const handleDragLeave = () => {
    // We don't clear dragOverId on leave because dragover is fired repeatedly
    // as the cursor moves across child nodes inside the li; clearing here
    // would produce flicker. The next over on a different li overwrites the
    // value; drop/end clean it up.
  };

  const handleDragEnd = () => {
    setDraggingId(null);
    setDragOverId(null);
  };

  const handleDrop = (targetId: number) => async (e: ReactDragEvent<HTMLLIElement>) => {
    e.preventDefault();
    const sourceId = draggingId;
    setDraggingId(null);
    setDragOverId(null);
    if (sourceId == null || sourceId === targetId || accountId == null) return;

    const currentOrder = sorted.map((v) => v.id);
    const nextOrder = moveIdToBefore(currentOrder, sourceId, targetId);
    if (nextOrder === currentOrder) return;

    // Optimistic: re-stamp orderIndex locally so the UI doesn't snap back
    // before the server responds. The server's return value re-hydrates the
    // full list (including any other field changes since last fetch).
    const byId = new Map(villages.map((v) => [v.id, v]));
    const optimistic: Village[] = nextOrder
      .map((id, idx) => {
        const v = byId.get(id);
        return v ? { ...v, orderIndex: idx } : null;
      })
      .filter((v): v is Village => v != null);
    setVillages(optimistic);

    try {
      const fresh = await villagesApi.reorder(accountId, nextOrder);
      setVillages(fresh);
    } catch (err) {
      setReorderError(err instanceof Error ? err.message : 'Reorder failed');
      // On failure re-fetch so we don't strand the UI in an invalid
      // optimistic state. The list endpoint is cheap and always authoritative.
      try {
        const reloaded = await villagesApi.list(accountId);
        setVillages(reloaded);
      } catch {
        /* leave the optimistic state; user will retry or refresh */
      }
    }
  };

  return (
    <div className="stack stack--xwide">
      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to see its villages.
          </p>
        </Card>
      )}

      {accountId != null && (
        <div
          className={
            'village-master-detail' +
            (villagesCollapsed ? ' village-master-detail--collapsed' : '')
          }
        >
          {!villagesCollapsed && (
            <Card
              title={
                <span className="card__title-row">
                  <span>Villages</span>
                  {/* Collapse handle for the villages list. Lives in the
                      Villages card header now (was on the tab toolbar) so
                      the gesture is co-located with the list it controls.
                      The expand counterpart sits on the right pane's tab
                      toolbar so the user can always re-open the list when
                      it's hidden. */}
                  <button
                    type="button"
                    className="card__title-toggle"
                    onClick={() => setVillagesCollapsed(true)}
                    aria-label="Hide villages list"
                    title="Hide villages list"
                  >
                    ‹
                  </button>
                </span>
              }
              hint="Drag rows to reorder."
            >
              {loading && <p className="muted">Loading…</p>}
              {error && <p className="form__msg form__msg--error">{error}</p>}
              {reorderError && <p className="form__msg form__msg--error">{reorderError}</p>}
              {!loading && !error && sorted.length === 0 && (
                <p className="muted" style={{ margin: 0 }}>
                  None yet. Log in on the <Link to={`/accounts/${accountId}`} className="link">account page</Link>{' '}
                  — the first village is scraped automatically.
                </p>
              )}
              {!loading && !error && sorted.length > 0 && (
                <ul
                  className="village-list"
                  style={{
                    listStyle: 'none',
                    padding: 0,
                    margin: 0,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.15rem',
                  }}
                >
                  {sorted.map((v) => {
                    const active = v.id === selectedId;
                    const dragging = v.id === draggingId;
                    const dragOver = v.id === dragOverId && draggingId != null && draggingId !== v.id;
                    const rowClass = [
                      'village-row',
                      active ? 'village-row--active' : '',
                      dragging ? 'village-row--dragging' : '',
                      dragOver ? 'village-row--drag-over' : '',
                    ].filter(Boolean).join(' ');
                    return (
                      <li
                        key={v.id}
                        onDragOver={handleDragOver(v.id)}
                        onDragLeave={handleDragLeave}
                        onDrop={handleDrop(v.id)}
                      >
                        <button
                          type="button"
                          onClick={() => selectVillage(v.id)}
                          className={rowClass}
                          draggable
                          onDragStart={handleDragStart(v.id)}
                          onDragEnd={handleDragEnd}
                          title="Drag to reorder"
                        >
                          <span style={{ display: 'flex', justifyContent: 'space-between', gap: '0.5rem' }}>
                            <strong style={{ fontSize: '0.9rem' }}>{v.name}</strong>
                            {v.capital && <span className="pill pill--success" style={{ fontSize: '0.65rem' }}>Cap</span>}
                          </span>
                          <span className="village-row__meta" style={{ fontSize: '0.72rem' }}>
                            ({v.coordinateX}|{v.coordinateY})
                            {v.population != null && ` · pop ${v.population}`}
                            {v.queue.length > 0 && ` · ${v.queue.length} queued`}
                          </span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </Card>
          )}

          <div>
            {selectedId == null ? (
              <Card>
                <p className="muted" style={{ margin: 0 }}>
                  Select a village on the left to see its details.
                </p>
              </Card>
            ) : (
              <VillageDetailPanel
                key={selectedId}
                villageId={selectedId}
                villagesCollapsed={villagesCollapsed}
                onToggleVillagesCollapsed={() => setVillagesCollapsed((c) => !c)}
                onReloadAll={reloadAllVillages}
                reloadState={reloadState}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
