import { useCallback, useEffect, useMemo, useState } from 'react';
import Card from '../../components/Card';
import BuildingQueueEditor from './BuildingQueueEditor';
import CompactBuildingsGrid from './CompactBuildingsGrid';
import CompactResourceFieldsGrid from './CompactResourceFieldsGrid';
import { villagesApi } from '../../api/villages';
import { ApiException } from '../../types/api';
import type { Building, BuildingQueueItem, ResourceField, Village } from '../../types/village';

/**
 * Resource fields + Buildings + Build queue for one village. Extracted from
 * VillageDetailPanel when that component was split into tabs — the build
 * workflow is big enough to own its own file.
 * <p>
 * We re-fetch the village here (rather than taking it as a prop) so each tab
 * can own its own load state and error handling. Saves a "why is my tab
 * empty when I click back" bug where the parent had stale state.
 *
 * <h3>Save & Run</h3>
 * The primary "Save & Run" button saves the whole queue and immediately
 * starts the first eligible PENDING row (has id, has slot, nothing else is
 * RUNNING). That mirrors what the backend scheduler does on its next tick —
 * we just do it now so the user sees action instead of a "pending" row
 * sitting there for 15 minutes. If starting fails (not logged in, capacity
 * full) we surface the error but keep the saved queue.
 *
 * <h3>Auto-refresh</h3>
 * When a RUNNING row's {@code finishesAt} elapses, {@link BuildingQueueEditor}
 * fires {@code onFinished}; we re-fetch the village so the reconciler's
 * DONE / PENDING transition surfaces immediately. As a belt-and-braces we
 * also poll every 15s while a RUNNING row exists, because the scraper may
 * not have run yet when the finish-time elapses.
 */
interface Props {
  villageId: number;
}

type State =
  | { kind: 'loading' }
  | { kind: 'found'; village: Village }
  | { kind: 'missing' }
  | { kind: 'error'; message: string };

type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

const AUTO_REFRESH_MS = 15_000;

export default function VillageBuildTab({ villageId }: Props) {
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [queueDraft, setQueueDraft] = useState<BuildingQueueItem[]>([]);
  const [queueSaveState, setQueueSaveState] = useState<SaveState>({ kind: 'idle' });
  const [lastRefreshedAt, setLastRefreshedAt] = useState<number | null>(null);

  // Single "load me" helper so the initial fetch, the auto-refresh hooks,
  // and the in-flight poll timer all share the same entry point. Returns a
  // promise resolving to the refreshed village (or null on 404/error) so
  // callers can chain.
  const fetchVillage = useCallback(async (): Promise<Village | null> => {
    try {
      const v = await villagesApi.get(villageId);
      setState({ kind: 'found', village: v });
      setQueueDraft(v.queue);
      setLastRefreshedAt(Date.now());
      return v;
    } catch (err) {
      if (err instanceof ApiException && err.status === 404) {
        setState({ kind: 'missing' });
      } else {
        const message = err instanceof Error ? err.message : 'Failed to load village';
        setState({ kind: 'error', message });
      }
      return null;
    }
  }, [villageId]);

  useEffect(() => {
    let cancelled = false;
    setState({ kind: 'loading' });
    setQueueSaveState({ kind: 'idle' });
    (async () => {
      const v = await fetchVillage();
      if (cancelled) return;
      void v;
    })();
    return () => { cancelled = true; };
  }, [fetchVillage]);

  function applyLoaded(v: Village) {
    setState({ kind: 'found', village: v });
    setQueueDraft(v.queue);
    setLastRefreshedAt(Date.now());
  }

  const currentLevels = useMemo<Map<number, number>>(() => {
    const out = new Map<number, number>();
    if (state.kind !== 'found') return out;
    for (const f of state.village.resourceFields) out.set(f.slotId, f.level);
    for (const b of state.village.buildings) out.set(b.slotId, b.level);
    return out;
  }, [state]);

  // Belt-and-braces poll: while anything is RUNNING, re-fetch every 15s so
  // the scraper-driven RUNNING→DONE flip can surface even if the user's tab
  // has been idle for a while.
  const hasRunning = useMemo(
    () => queueDraft.some((it) => it.status === 'RUNNING'),
    [queueDraft],
  );
  useEffect(() => {
    if (!hasRunning) return;
    const id = window.setInterval(() => {
      void fetchVillage();
    }, AUTO_REFRESH_MS);
    return () => window.clearInterval(id);
  }, [hasRunning, fetchVillage]);

  if (state.kind === 'loading') return <p className="muted">Loading village…</p>;
  if (state.kind === 'missing') {
    return <p className="muted">Village not found — maybe it was deleted elsewhere.</p>;
  }
  if (state.kind === 'error') return <p className="form__msg form__msg--error">{state.message}</p>;

  const v = state.village;

  /**
   * Core "save, then kick the first eligible PENDING row" helper. Used by
   * the tile "+ upgrade" buttons (which append a single row) and by the
   * queue editor's "Save & Run" primary save button.
   * <p>
   * Returns the persisted village and optionally the id we attempted to
   * start so callers can thread an error message. Start failures don't
   * undo the save — the queue is persisted regardless.
   */
  const saveAndRun = async (
    nextQueue: BuildingQueueItem[],
    preferredMatch: { slot: number; buildingType: string; targetLevel: number } | null,
  ): Promise<void> => {
    setQueueSaveState({ kind: 'saving' });
    let savedVillage: Village;
    try {
      savedVillage = await villagesApi.replaceQueue(v.id, nextQueue);
      applyLoaded(savedVillage);
      setQueueSaveState({ kind: 'saved' });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Save failed';
      setQueueSaveState({ kind: 'error', message });
      return;
    }

    // Don't start anything if a row is already RUNNING on this village — the
    // scheduler + Travian both refuse concurrent builds outside tribe/plus
    // capacity, and the Start endpoint would error for nothing.
    if (savedVillage.queue.some((r) => r.status === 'RUNNING')) return;

    // Prefer the exact row the user just added (matched by slot/type/target)
    // if the caller supplied one. Fall back to the first PENDING row with a
    // slot — same policy as the backend scheduler.
    let target: BuildingQueueItem | undefined;
    if (preferredMatch) {
      const matches = savedVillage.queue.filter(
        (r) =>
          r.status === 'PENDING' &&
          r.slot === preferredMatch.slot &&
          r.buildingType === preferredMatch.buildingType &&
          r.targetLevel === preferredMatch.targetLevel,
      );
      matches.sort((a, b) => b.orderIndex - a.orderIndex);
      target = matches[0];
    }
    if (!target) {
      target = savedVillage.queue.find((r) => r.status === 'PENDING' && r.slot != null);
    }
    if (!target || target.id == null) return; // nothing runnable — silent no-op

    await startQueueItem(target.id);
  };

  const queueResourceUpgrade = (field: ResourceField, targetLevel: number) => {
    const type = resourceBuildingType(field);
    const newRow: BuildingQueueItem = {
      id: null,
      buildingType: type,
      slot: field.slotId,
      targetLevel,
      status: 'PENDING',
      createdAt: null,
      completedAt: null,
      finishesAt: null,
      resultMessage: null,
      orderIndex: queueDraft.length,
    };
    void saveAndRun(
      [...queueDraft, newRow],
      { slot: field.slotId, buildingType: type, targetLevel },
    );
  };

  const queueBuildingUpgrade = (building: Building, targetLevel: number) => {
    const type = (building.name ?? 'UNKNOWN').toUpperCase().replace(/\s+/g, '_');
    const newRow: BuildingQueueItem = {
      id: null,
      buildingType: type,
      slot: building.slotId,
      targetLevel,
      status: 'PENDING',
      createdAt: null,
      completedAt: null,
      finishesAt: null,
      resultMessage: null,
      orderIndex: queueDraft.length,
    };
    void saveAndRun(
      [...queueDraft, newRow],
      { slot: building.slotId, buildingType: type, targetLevel },
    );
  };

  const queueNewBuilding = (slotId: number, buildingType: string) => {
    const newRow: BuildingQueueItem = {
      id: null,
      buildingType,
      slot: slotId,
      targetLevel: 1,
      status: 'PENDING',
      createdAt: null,
      completedAt: null,
      finishesAt: null,
      resultMessage: null,
      orderIndex: queueDraft.length,
    };
    void saveAndRun(
      [...queueDraft, newRow],
      { slot: slotId, buildingType, targetLevel: 1 },
    );
  };

  // "Save" alone (no run) — for when the user just reordered or trimmed rows
  // and doesn't want to trigger a new in-game click.
  const saveOnly = async () => {
    setQueueSaveState({ kind: 'saving' });
    try {
      const updated = await villagesApi.replaceQueue(v.id, queueDraft);
      applyLoaded(updated);
      setQueueSaveState({ kind: 'saved' });
    } catch (err) {
      setQueueSaveState({ kind: 'error', message: err instanceof Error ? err.message : 'Save failed' });
    }
  };

  const saveAndRunCurrentDraft = () => {
    void saveAndRun(queueDraft, null);
  };

  // Internal helper used by saveAndRun() to kick off a saved PENDING row.
  // No longer surfaced as a per-row Run button — the queue editor is now
  // read-only — but Save & Run still needs the same call to start the next
  // pending row in-game. Errors are silently swallowed: the row stays
  // PENDING and the scheduler will pick it up on its next tick once the
  // session is back. We deliberately don't show an inline "not logged in"
  // notice here — the session red-dot in the sidebar is the canonical
  // surface for that state, and double-warning was noise.
  const startQueueItem = async (queueItemId: number) => {
    try {
      const updated = await villagesApi.startQueueItem(v.id, queueItemId);
      applyLoaded(updated);
    } catch {
      /* swallow — see comment above */
    }
  };

  // Counts for the queue summary chip: pending, running, failed. Helps the
  // user see at a glance what the queue is actually doing without needing to
  // scan the table on every tab switch.
  const visibleQueue = queueDraft.filter((it) => it.status !== 'DONE');
  const queueCounts = {
    running: visibleQueue.filter((it) => it.status === 'RUNNING').length,
    pending: visibleQueue.filter((it) => it.status === 'PENDING').length,
    failed: visibleQueue.filter((it) => it.status === 'FAILED').length,
  };

  return (
    <div className="stack stack--xwide build-tab">
      <Card title="Resource fields" hint="Click a tile to queue and start its next level immediately.">
        <CompactResourceFieldsGrid
          fields={v.resourceFields}
          queue={queueDraft}
          onQueueUpgrade={queueResourceUpgrade}
        />
      </Card>

      <Card title="Buildings" hint="Click a built tile to queue its next level. Click an empty slot to pick what to build there.">
        <CompactBuildingsGrid
          buildings={v.buildings}
          queue={queueDraft}
          onQueueUpgrade={queueBuildingUpgrade}
          onQueueNew={queueNewBuilding}
        />
      </Card>

      <Card
        title={
          <span className="queue-card__title">
            <span>Build queue</span>
            <span className="queue-card__counts">
              {queueCounts.running > 0 && (
                <span className="pill pill--info">{queueCounts.running} running</span>
              )}
              {queueCounts.pending > 0 && (
                <span className="pill">{queueCounts.pending} pending</span>
              )}
              {queueCounts.failed > 0 && (
                <span className="pill pill--error">{queueCounts.failed} failed</span>
              )}
              {queueCounts.running + queueCounts.pending + queueCounts.failed === 0 && (
                <span className="muted">empty</span>
              )}
            </span>
          </span>
        }
        hint="Rows are added by clicking a tile above. Reorder with the arrows or remove with ✕. “Save & Run” saves the queue and kicks off the next build in-game; “Save” only persists edits."
      >
        <BuildingQueueEditor
          items={visibleQueue}
          onChange={(visible) => {
            const done = queueDraft.filter((it) => it.status === 'DONE');
            setQueueDraft([...done, ...visible]);
          }}
          currentLevels={currentLevels}
          onFinished={() => { void fetchVillage(); }}
        />
        <div className="settings-savebar" style={{ marginTop: '1rem' }}>
          <span className="settings-savebar__msg">
            {queueSaveState.kind === 'saving' && <span className="muted">Saving…</span>}
            {queueSaveState.kind === 'saved' && 'Queue saved.'}
            {queueSaveState.kind === 'error' && <span className="form__msg form__msg--error">{queueSaveState.message}</span>}
            {lastRefreshedAt != null && queueSaveState.kind === 'idle' && (
              <span className="muted" style={{ fontSize: '0.75rem' }}>
                Last refreshed {formatClockFromMs(lastRefreshedAt)}
              </span>
            )}
          </span>
          <button type="button" className="btn btn--ghost" onClick={() => setQueueDraft(v.queue)}>
            Reset
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => { void saveOnly(); }}
            disabled={queueSaveState.kind === 'saving'}
            title="Save the queue without starting a new build in-game"
          >
            Save
          </button>
          <button
            type="button"
            className="btn btn--primary"
            onClick={saveAndRunCurrentDraft}
            disabled={queueSaveState.kind === 'saving'}
            title="Save the queue and immediately start the next pending build in-game"
          >
            {queueSaveState.kind === 'saving' ? 'Saving…' : 'Save & Run'}
          </button>
        </div>
      </Card>
    </div>
  );
}

function resourceBuildingType(field: ResourceField): string {
  switch (field.type) {
    case 'WOOD': return 'WOODCUTTER';
    case 'CLAY': return 'CLAY_PIT';
    case 'IRON': return 'IRON_MINE';
    case 'CROP': return 'CROPLAND';
  }
}

function formatClockFromMs(ms: number): string {
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}
