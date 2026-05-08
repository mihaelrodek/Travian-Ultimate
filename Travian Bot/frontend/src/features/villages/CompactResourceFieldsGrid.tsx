import type { BuildingQueueItem, ResourceField, ResourceType } from '../../types/village';

/**
 * Compact 9-wide grid of the 18 outside-village resource tiles. Two rows of
 * nine fits the dorf1 layout cleanly without forcing the player to scroll, and
 * leaves the queue card below comfortably visible on a typical 1080p browser.
 * <p>
 * Each tile is color-coded by resource type via {@code .resource-tile--<type>}
 * (defined in index.css) so the player can scan the village at a glance —
 * Travian itself uses similar tinting, and the colored band on the left edge
 * makes the resource type readable without the wordy "Wood/Clay/Iron/Crop"
 * label competing for space inside a 70-pixel-wide tile.
 * <p>
 * The "+" upgrade button bumps the next-target by one level (current →
 * current + 1, or queued-target → queued-target + 1 if a row is already
 * pending). Wholly intentional: the grid is for fast queueing, not for
 * deciding "to what level". The build queue editor below is the place to
 * fine-tune target levels.
 */
interface Props {
  fields: ResourceField[];
  queue: BuildingQueueItem[];
  onQueueUpgrade: (field: ResourceField, targetLevel: number) => void;
}

/**
 * Maps our resource-type string to the legacy Travian GID so queue rows match
 * the backend's expected enum values. Kept small and local because this is
 * the only component that needs the reverse mapping.
 */
const RESOURCE_TYPE_TO_BUILDING_TYPE: Record<ResourceType, string> = {
  WOOD: 'WOODCUTTER',
  CLAY: 'CLAY_PIT',
  IRON: 'IRON_MINE',
  CROP: 'CROPLAND',
};

export default function CompactResourceFieldsGrid({ fields, queue, onQueueUpgrade }: Props) {
  if (fields.length === 0) {
    return (
      <p className="muted">
        No resource field data yet. Use <strong>Reload all villages</strong> in the toolbar
        above to populate this view.
      </p>
    );
  }

  const bySlot = new Map<number, ResourceField>();
  for (const f of fields) bySlot.set(f.slotId, f);

  return (
    <div className="resource-grid">
      {Array.from({ length: 18 }, (_, i) => i + 1).map((slot) => {
        const f = bySlot.get(slot);
        if (!f) {
          return (
            <div key={slot} className="tile tile--muted resource-tile resource-tile--empty">
              <span className="muted tile__meta">unscraped</span>
            </div>
          );
        }
        const queuedTarget = latestQueuedTargetForSlot(queue, f.slotId, RESOURCE_TYPE_TO_BUILDING_TYPE[f.type]);
        const nextLevel = Math.max(f.level, queuedTarget ?? 0) + 1;
        return (
          <button
            key={slot}
            type="button"
            className={`tile resource-tile resource-tile--${f.type.toLowerCase()}`}
            onClick={() => onQueueUpgrade(f, nextLevel)}
            title={`${formatType(f.type)} · click to queue Level ${nextLevel}`}
            aria-label={`Queue ${formatType(f.type)} to level ${nextLevel}`}
          >
            <span className="resource-tile__icon" aria-hidden>
              {RESOURCE_GLYPH[f.type]}
            </span>
            <span className="resource-tile__level">
              <strong>{f.level}</strong>
              {f.underConstruction && <span className="badge badge--running">…</span>}
            </span>
            {/*
              Bottom row: queued delta on the LEFT (e.g. "+2" when queue
              targets two levels above current), upgrade-arrow on the RIGHT.
              Reserve the left slot with an empty span when nothing is
              queued so the arrow doesn't shift between idle and queued
              states. The full target level is in the title attribute for
              users who hover.
            */}
            <span className="resource-tile__bottom">
              {queuedTarget != null ? (
                <span
                  className="resource-tile__delta"
                  title={`Queued → Level ${queuedTarget}`}
                >
                  +{queuedTarget - f.level}
                </span>
              ) : (
                <span className="resource-tile__delta resource-tile__delta--empty" aria-hidden />
              )}
              <span className="resource-tile__cta" aria-hidden>↑</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

const RESOURCE_GLYPH: Record<ResourceType, string> = {
  WOOD: '🌲',
  CLAY: '🧱',
  IRON: '⛓',
  CROP: '🌾',
};

function formatType(type: ResourceType): string {
  switch (type) {
    case 'WOOD': return 'Wood';
    case 'CLAY': return 'Clay';
    case 'IRON': return 'Iron';
    case 'CROP': return 'Crop';
  }
}

/**
 * Highest target level already queued for a given slot/building combo, or null
 * if nothing queued yet. Matching on slot is primary so the player's intent is
 * respected even if they queued the same building in two places.
 */
function latestQueuedTargetForSlot(
  queue: BuildingQueueItem[],
  slotId: number,
  buildingType: string,
): number | null {
  let max: number | null = null;
  for (const it of queue) {
    if (it.status !== 'PENDING') continue;
    const slotMatch = it.slot === slotId || (it.slot == null && it.buildingType === buildingType);
    if (!slotMatch) continue;
    if (max == null || it.targetLevel > max) max = it.targetLevel;
  }
  return max;
}
