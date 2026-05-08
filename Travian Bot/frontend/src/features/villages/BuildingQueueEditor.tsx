import { useEffect, useMemo, useState, type DragEvent as ReactDragEvent } from 'react';
import type { BuildingQueueItem } from '../../types/village';
import { formatRemaining } from '../../utils/time';

/**
 * Read-only queue viewer with reorder + remove affordances.
 * <p>
 * The queue is now seeded entirely from the resource/building tile clicks
 * above — there's no inline "add" form or building/slot/level editor here.
 * The user can:
 *   - reorder rows by dragging them, or with the ↑ / ↓ buttons (orderIndex
 *     is restamped on every change so the wire-format the parent ships
 *     matches the displayed order),
 *   - remove a row (anything except a RUNNING row).
 * <p>
 * For RUNNING rows we surface a live "in HH:MM:SS" countdown next to the
 * status pill — there's no separate Finish column anymore. The 1-second
 * ticker is only mounted when at least one row is RUNNING; an idle queue
 * costs zero CPU.
 * <p>
 * When {@code finishesAt} elapses we fire {@code onFinished} once per row
 * (deduped by id) so the parent can re-fetch the village and let the
 * reconciler flip the status to DONE/PENDING.
 */
interface Props {
  items: BuildingQueueItem[];
  onChange: (items: BuildingQueueItem[]) => void;
  /** Current building/field levels keyed by slotId — used to display "L3 → L4". */
  currentLevels: Map<number, number>;
  /**
   * Fired once per RUNNING row whose {@code finishesAt} just elapsed. Parent
   * uses it to re-fetch the village so the row can transition to DONE/PENDING
   * without a manual refresh.
   */
  onFinished?: () => void;
}

export default function BuildingQueueEditor({
  items,
  onChange,
  currentLevels,
  onFinished,
}: Props) {
  // Per-second ticker — only runs while there's a RUNNING row. The interval
  // is torn down the moment everything is PENDING/DONE/FAILED so an idle
  // queue tab costs zero CPU.
  const hasRunning = useMemo(() => items.some((it) => it.status === 'RUNNING'), [items]);
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    if (!hasRunning) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [hasRunning]);

  // Fire onFinished once for each row whose finishesAt has just crossed the
  // current clock. We de-dupe by row id so a long-lived RUNNING row (whose
  // finishesAt keeps "being in the past" until the reconciler flips it) only
  // triggers one refresh per row — otherwise we'd hammer the village endpoint.
  const [firedFinishedIds, setFiredFinishedIds] = useState<Set<string>>(() => new Set());
  useEffect(() => {
    if (!onFinished) return;
    const newlyFinished: string[] = [];
    for (const it of items) {
      if (it.status !== 'RUNNING' || !it.finishesAt) continue;
      const ms = Date.parse(it.finishesAt);
      if (Number.isNaN(ms) || ms > now) continue;
      const key = it.id != null ? `id:${it.id}` : `new:${it.orderIndex}:${it.finishesAt}`;
      if (firedFinishedIds.has(key)) continue;
      newlyFinished.push(key);
    }
    if (newlyFinished.length === 0) return;
    setFiredFinishedIds((prev) => {
      const next = new Set(prev);
      for (const k of newlyFinished) next.add(k);
      return next;
    });
    onFinished();
  }, [items, now, onFinished, firedFinishedIds]);

  // Whenever items churn (parent re-fetched and row ids rotated), purge any
  // fired keys that no longer correspond to a RUNNING row so a future RUNNING
  // phase on the same row can still trigger.
  useEffect(() => {
    const alive = new Set<string>();
    for (const it of items) {
      if (it.status !== 'RUNNING' || !it.finishesAt) continue;
      const key = it.id != null ? `id:${it.id}` : `new:${it.orderIndex}:${it.finishesAt}`;
      alive.add(key);
    }
    setFiredFinishedIds((prev) => {
      let changed = false;
      const next = new Set<string>();
      for (const k of prev) {
        if (alive.has(k)) next.add(k);
        else changed = true;
      }
      return changed ? next : prev;
    });
  }, [items]);

  // Surfaced when the user attempts a reorder that would put a higher target
  // level of the same building before a lower one (e.g. Main Building L3
  // before Main Building L1). The backend would happily accept either order
  // — the constraint is purely a UX guard so the player doesn't queue
  // something the in-game upgrade rules will refuse later.
  const [reorderError, setReorderError] = useState<string | null>(null);

  /**
   * Re-stamp every row's orderIndex to match its array position before
   * pushing it back to the parent. Without this the move/remove ops would
   * leave stale orderIndex values on the wire — the backend reconciler then
   * re-sorts by orderIndex and reverses the user's edit (which was the
   * "buggy reorder" symptom). Single source of truth: array position.
   */
  const restamp = (next: BuildingQueueItem[]) =>
    next.map((it, idx) => (it.orderIndex === idx ? it : { ...it, orderIndex: idx }));

  /**
   * Validate a proposed ordering — returns an error message if any
   * (buildingType, slot) appears with a higher targetLevel ahead of a lower
   * one. Same building can appear multiple times only in ascending level
   * order (you can't upgrade L3 before L1 lands).
   * <p>
   * Slot-keyed instead of buildingType-only so two different empty Main
   * Buildings on different villages don't collide if a future edit ever
   * lets that happen — though today the queue is per-village so the slot
   * uniquely identifies the row.
   */
  const validateOrder = (next: BuildingQueueItem[]): string | null => {
    const lastLevelByKey = new Map<string, { level: number; rowIndex: number }>();
    for (let i = 0; i < next.length; i++) {
      const it = next[i];
      // Only PENDING rows participate in the check — RUNNING / DONE / FAILED
      // rows are scenery here and the backend wouldn't reorder them anyway.
      if (it.status !== 'PENDING') continue;
      const key = `${it.buildingType}#${it.slot ?? '-'}`;
      const prev = lastLevelByKey.get(key);
      if (prev && prev.level >= it.targetLevel) {
        return `${humanize(it.buildingType)} L${it.targetLevel} cannot run before `
            + `L${prev.level} (row ${prev.rowIndex + 1}). Same-building rows must `
            + `be in ascending level order.`;
      }
      lastLevelByKey.set(key, { level: it.targetLevel, rowIndex: i });
    }
    return null;
  };

  /**
   * Wraps {@link onChange} with the level-order check. Reverts to the prior
   * state and surfaces an error if the proposed change is invalid; otherwise
   * commits and clears any prior reorder error.
   */
  const commit = (next: BuildingQueueItem[]) => {
    const err = validateOrder(next);
    if (err) {
      setReorderError(err);
      return;
    }
    setReorderError(null);
    onChange(restamp(next));
  };

  const remove = (index: number) => {
    commit(items.filter((_, i) => i !== index));
  };

  const move = (index: number, delta: -1 | 1) => {
    const target = index + delta;
    if (target < 0 || target >= items.length) return;
    const next = [...items];
    const [m] = next.splice(index, 1);
    next.splice(target, 0, m);
    commit(next);
  };

  // ---------- HTML5 drag-and-drop reordering ----------
  // Same shape as the village list on VillagesListPage: track which row is
  // being dragged and which row is currently the drop target so we can render
  // a top-edge indicator. RUNNING rows are not draggable (the in-game build
  // is already underway and reorder would lose track) and the drop target
  // bookkeeping clears itself on drop / dragend.
  const [draggingIdx, setDraggingIdx] = useState<number | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);

  const onRowDragStart = (idx: number) => (e: ReactDragEvent<HTMLTableRowElement>) => {
    setDraggingIdx(idx);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(idx));
  };

  const onRowDragOver = (idx: number) => (e: ReactDragEvent<HTMLTableRowElement>) => {
    if (draggingIdx == null) return;
    const target = items[idx];
    if (target?.status === 'RUNNING') return; // Can't insert above a running row.
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragOverIdx !== idx) setDragOverIdx(idx);
  };

  const onRowDragEnd = () => {
    setDraggingIdx(null);
    setDragOverIdx(null);
  };

  const onRowDrop = (targetIdx: number) => (e: ReactDragEvent<HTMLTableRowElement>) => {
    e.preventDefault();
    const src = draggingIdx;
    setDraggingIdx(null);
    setDragOverIdx(null);
    if (src == null || src === targetIdx) return;
    const next = [...items];
    const [moved] = next.splice(src, 1);
    // Insert before the target slot — if dragging from below the target, the
    // splice already shifted indices, so insert at the original targetIdx.
    const insertAt = src < targetIdx ? targetIdx - 1 : targetIdx;
    next.splice(insertAt, 0, moved);
    commit(next);
  };

  if (items.length === 0) {
    return <p className="muted queue-empty">Build queue — empty.</p>;
  }

  return (
    <div className="queue">
      {reorderError && (
        <p
          className="form__msg form__msg--error"
          style={{ marginTop: 0, marginBottom: '0.5rem' }}
          role="alert"
          onAnimationEnd={() => setReorderError(null)}
        >
          {reorderError}
        </p>
      )}
      <table className="table table--compact queue-table">
        <thead>
          <tr>
            <th style={{ width: '2.5rem' }}>#</th>
            <th>Building</th>
            <th style={{ width: '5rem' }}>Slot</th>
            <th style={{ width: '6.5rem' }}>Level</th>
            <th style={{ width: '12rem' }}>Status</th>
            <th style={{ width: '8rem' }} />
          </tr>
        </thead>
        <tbody>
          {items.map((it, i) => {
            const currentLvl = it.slot != null ? currentLevels.get(it.slot) ?? null : null;
            const draggable = it.status === 'PENDING';
            const dragging = draggingIdx === i;
            const dragOver = dragOverIdx === i && draggingIdx != null && draggingIdx !== i;
            const rowClass = [
              statusRowClass(it.status),
              dragging ? 'queue-row--dragging' : '',
              dragOver ? 'queue-row--drag-over' : '',
            ].filter(Boolean).join(' ');
            return (
              <tr
                key={it.id ?? `new-${i}`}
                className={rowClass}
                draggable={draggable}
                onDragStart={draggable ? onRowDragStart(i) : undefined}
                onDragOver={draggable || dragOverIdx === i ? onRowDragOver(i) : undefined}
                onDrop={onRowDrop(i)}
                onDragEnd={onRowDragEnd}
                title={draggable ? 'Drag to reorder' : undefined}
                style={draggable ? { cursor: 'grab' } : undefined}
              >
                <td className="muted">{i + 1}</td>
                <td>
                  <span className="queue-cell__name">{humanize(it.buildingType)}</span>
                </td>
                <td className="muted" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {it.slot ?? '—'}
                </td>
                <td>
                  <span className="queue-cell__level">
                    <span className="muted">{currentLvl ?? '–'}</span>
                    <span className="muted">→</span>
                    <strong>{it.targetLevel}</strong>
                  </span>
                </td>
                <td>{renderStatusCell(it, now)}</td>
                <td>
                  <div className="queue-actions">
                    {/*
                      Reorder + remove. Both are disabled for RUNNING rows
                      because the in-game build is already underway and we'd
                      lose track. The scheduler runs the next PENDING row
                      automatically as soon as a builder slot frees up — no
                      manual Run button needed.
                    */}
                    <button
                      type="button"
                      className="btn btn--sm btn--ghost"
                      onClick={() => move(i, -1)}
                      disabled={i === 0 || it.status !== 'PENDING'}
                      aria-label="Move up"
                      title="Move up"
                    >↑</button>
                    <button
                      type="button"
                      className="btn btn--sm btn--ghost"
                      onClick={() => move(i, 1)}
                      disabled={i === items.length - 1 || it.status !== 'PENDING'}
                      aria-label="Move down"
                      title="Move down"
                    >↓</button>
                    <button
                      type="button"
                      className="btn btn--sm btn--danger"
                      onClick={() => remove(i)}
                      disabled={it.status === 'RUNNING'}
                      aria-label="Remove"
                      title={it.status === 'RUNNING' ? 'Running — cannot remove' : 'Remove'}
                    >✕</button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function humanize(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}

function statusRowClass(status: BuildingQueueItem['status']): string | undefined {
  switch (status) {
    case 'RUNNING': return 'queue-row--running';
    case 'FAILED': return 'queue-row--failed';
    case 'DONE': return 'queue-row--done';
    default: return undefined;
  }
}

function statusPillClass(status: BuildingQueueItem['status']): string {
  switch (status) {
    case 'DONE': return 'pill--success';
    case 'FAILED': return 'pill--error';
    case 'CANCELLED': return 'pill--muted';
    case 'RUNNING': return 'pill--info';
    default: return 'pill--neutral';
  }
}

/**
 * Status cell content. For RUNNING rows we inline the live remaining-time
 * (replacing the old standalone Finish column). For FAILED rows we show a
 * truncated reason next to the pill, with the full message in a tooltip.
 */
function renderStatusCell(it: BuildingQueueItem, now: number) {
  const pillClass = statusPillClass(it.status);
  const label = it.status.charAt(0) + it.status.slice(1).toLowerCase();

  let trailing: React.ReactNode = null;
  if (it.status === 'RUNNING' && it.finishesAt) {
    const remaining = formatRemaining(it.finishesAt, now);
    trailing = (
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        {remaining ? `in ${remaining}` : 'finishing…'}
      </span>
    );
  } else if (it.status === 'FAILED' && it.resultMessage) {
    trailing = (
      <span className="muted" title={it.resultMessage} style={{ fontSize: '0.75rem' }}>
        {truncate(it.resultMessage, 40)}
      </span>
    );
  }

  return (
    <div className="queue-status">
      {it.status === 'RUNNING' && <span className="queue-status__dot" aria-hidden />}
      <span className={`pill ${pillClass}`}>{label}</span>
      {trailing}
    </div>
  );
}

function truncate(s: string, n: number): string {
  return s.length <= n ? s : `${s.slice(0, n - 1)}…`;
}
