import { useState } from 'react';
import type { Building, BuildingQueueItem } from '../../types/village';
import { BUILDING_TYPES } from '../../types/village';

/**
 * Compact 7-column grid of the 22 inside-village building slots. Each occupied
 * slot is a clickable tile (click anywhere on the tile to queue the next
 * upgrade). Empty slots open an inline "what to build" picker so the player
 * can drop a new building into the queue without leaving the view.
 * <p>
 * Layout decision: 7 columns lays the 22 slots out in 4 rows
 * (7 + 7 + 7 + 1 = 22) with the trailing slot left to breathe — denser than
 * the old 6-wide grid, and the wider page (the toolbar collapse + wider stack
 * frees up horizontal real estate) makes that comfortable.
 * <p>
 * The whole tile is the click target: queueing the next upgrade is the most
 * common action, so making the player aim at a small "+" button was friction.
 * The picker for empty slots is still inline so the same tile can offer
 * "what should I build here" without a modal.
 */
interface Props {
  buildings: Building[];
  queue: BuildingQueueItem[];
  onQueueUpgrade: (building: Building, targetLevel: number) => void;
  onQueueNew: (slotId: number, buildingType: string) => void;
}

export default function CompactBuildingsGrid({ buildings, queue, onQueueUpgrade, onQueueNew }: Props) {
  // Which empty slot (if any) is currently showing the "pick a building" inline select.
  const [pickingSlot, setPickingSlot] = useState<number | null>(null);

  if (buildings.length === 0) {
    return (
      <p className="muted">
        No building data yet. Use <strong>Reload all villages</strong> in the toolbar
        above to populate this view.
      </p>
    );
  }

  const bySlot = new Map<number, Building>();
  for (const b of buildings) bySlot.set(b.slotId, b);

  // Buildings live in slots 19..40 — 22 tiles.
  const slots: number[] = [];
  for (let s = 19; s <= 40; s++) slots.push(s);

  return (
    <div className="building-grid">
      {slots.map((slot) => {
        const b = bySlot.get(slot);
        const isEmpty = !b || b.gid === 0;
        if (isEmpty) {
          const isPicking = pickingSlot === slot;
          if (isPicking) {
            // Types already in use elsewhere in the village shouldn't appear
            // in the picker — the player can't have two of the same building
            // (Travian itself enforces uniqueness for almost every slot 19-40
            // building; the few exceptions like Warehouse / Granary still
            // can't share a slot pre-existing one). Filter out:
            //   - any built building already present
            //   - any type already queued for another empty slot in this draft
            const unavailable = new Set<string>();
            for (const b of buildings) {
              if (b.gid !== 0 && b.name) {
                unavailable.add(b.name.toUpperCase().replace(/\s+/g, '_'));
              }
            }
            for (const it of queue) {
              if (it.status === 'DONE' || it.status === 'CANCELLED') continue;
              if (it.slot != null && it.slot !== slot) {
                // Track the type already destined for some other slot.
                unavailable.add(it.buildingType);
              }
            }
            return (
              <div key={slot} className="tile tile--muted building-tile building-tile--picking">
                <span className="tile__slot">{slot}</span>
                <NewBuildingPicker
                  unavailable={unavailable}
                  onCancel={() => setPickingSlot(null)}
                  onPick={(type) => {
                    setPickingSlot(null);
                    onQueueNew(slot, type);
                  }}
                />
              </div>
            );
          }
          return (
            <button
              key={slot}
              type="button"
              className="tile tile--muted building-tile building-tile--empty"
              onClick={() => setPickingSlot(slot)}
              title={`Slot ${slot} — click to choose a building`}
              aria-label={`Choose a building for empty slot ${slot}`}
            >
              <span className="tile__slot">{slot}</span>
              <span className="building-tile__placeholder">+ build</span>
            </button>
          );
        }
        const nonNull = b as Building;
        const queuedTarget = latestQueuedTargetForSlot(queue, nonNull.slotId);
        const nextLevel = Math.max(nonNull.level, queuedTarget ?? 0) + 1;
        const label = nonNull.name ?? `gid ${nonNull.gid}`;
        return (
          <button
            key={slot}
            type="button"
            className="tile building-tile"
            onClick={() => onQueueUpgrade(nonNull, nextLevel)}
            title={`${label} · Slot ${slot} · click to queue Level ${nextLevel}`}
            aria-label={`Queue ${label} slot ${slot} to level ${nextLevel}`}
          >
            <span className="tile__slot">{slot}</span>
            {nonNull.underConstruction && (
              <span className="badge badge--running building-tile__badge">…</span>
            )}
            <span className="building-tile__name">{label}</span>
            <span className="building-tile__level">
              <strong>{nonNull.level}</strong>
            </span>
            {/*
              Bottom row: queued delta on the LEFT (e.g. "+2" when the
              queue targets two levels above current), upgrade-arrow on
              the RIGHT. The empty placeholder span keeps the arrow
              anchored on the right when nothing is queued so the layout
              doesn't shift between idle and queued states.
            */}
            <span className="building-tile__bottom">
              {queuedTarget != null ? (
                <span
                  className="building-tile__delta"
                  title={`Queued → Level ${queuedTarget}`}
                >
                  +{queuedTarget - nonNull.level}
                </span>
              ) : (
                <span className="building-tile__delta building-tile__delta--empty" aria-hidden />
              )}
              <span className="building-tile__cta" aria-hidden>↑</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

/**
 * Empty-slot dropdown. Defaults to Main Building and fires {@code onPick} on
 * confirm. Filters out resource-field buildings from the options since those
 * can only be built via outside-village slots, not slots 19..40. Also hides
 * any type listed in {@code unavailable} so the player can't queue the same
 * building twice across two different empty slots.
 */
function NewBuildingPicker({
  unavailable,
  onPick,
  onCancel,
}: {
  unavailable: Set<string>;
  onPick: (buildingType: string) => void;
  onCancel: () => void;
}) {
  const options = BUILDING_TYPES.filter(
    (t) => !['WOODCUTTER', 'CLAY_PIT', 'IRON_MINE', 'CROPLAND'].includes(t) && !unavailable.has(t),
  );
  // Default the picker to the first available option, falling back to Main
  // Building. Falling back to "" would hand a blank value to onPick if every
  // type is taken — vanishingly rare but worth handling.
  const defaultType = options[0] ?? 'MAIN_BUILDING';
  const [type, setType] = useState<string>(defaultType);
  return (
    <div className="building-picker">
      <select
        value={type}
        onChange={(e) => setType(e.target.value)}
        className="building-picker__select"
      >
        {options.map((t) => (
          <option key={t} value={t}>{humanize(t)}</option>
        ))}
      </select>
      <div className="building-picker__actions">
        <button
          type="button"
          className="btn btn--sm btn--primary"
          onClick={() => onPick(type)}
        >
          add
        </button>
        <button
          type="button"
          className="btn btn--sm btn--ghost"
          onClick={onCancel}
          aria-label="Cancel"
        >
          ✕
        </button>
      </div>
    </div>
  );
}

function latestQueuedTargetForSlot(queue: BuildingQueueItem[], slotId: number): number | null {
  let max: number | null = null;
  for (const it of queue) {
    if (it.status !== 'PENDING') continue;
    if (it.slot !== slotId) continue;
    if (max == null || it.targetLevel > max) max = it.targetLevel;
  }
  return max;
}

function humanize(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}
