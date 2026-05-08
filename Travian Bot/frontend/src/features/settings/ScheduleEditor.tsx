import { DAYS_OF_WEEK, type ActiveWindow, type DayOfWeek } from '../../types/settings';
import { minutesToTime, timeToMinutes } from './timeUtils';

interface Props {
  value: ActiveWindow[];
  onChange: (next: ActiveWindow[]) => void;
}

const DAY_LABELS: Record<DayOfWeek, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
};

/** Day buckets returned by {@link groupByDay}. */
type DayBucket = Array<{ window: ActiveWindow; index: number }>;

/** Named defaults the user can drop in with one click. */
const PRESETS: Array<{ id: string; label: string; title: string; build: () => ActiveWindow[] }> = [
  {
    id: 'always',
    label: '24/7',
    title: 'Bot runs whenever — every day, all hours.',
    build: () => DAYS_OF_WEEK.map((d) => ({ dayOfWeek: d, startMinute: 0, endMinute: 24 * 60 })),
  },
  {
    id: 'day',
    label: 'Day (08–22)',
    title: 'Active 08:00–22:00 every day. Good baseline — avoids the overnight "always-on" giveaway.',
    build: () => DAYS_OF_WEEK.map((d) => ({ dayOfWeek: d, startMinute: 8 * 60, endMinute: 22 * 60 })),
  },
  {
    id: 'workweek',
    label: 'Workweek (Mon–Fri 17–23)',
    title: 'Evenings after work Mon–Fri, plus full weekends. A realistic player-style schedule.',
    build: () => [
      { dayOfWeek: 'MONDAY', startMinute: 17 * 60, endMinute: 23 * 60 },
      { dayOfWeek: 'TUESDAY', startMinute: 17 * 60, endMinute: 23 * 60 },
      { dayOfWeek: 'WEDNESDAY', startMinute: 17 * 60, endMinute: 23 * 60 },
      { dayOfWeek: 'THURSDAY', startMinute: 17 * 60, endMinute: 23 * 60 },
      { dayOfWeek: 'FRIDAY', startMinute: 17 * 60, endMinute: 23 * 60 },
      { dayOfWeek: 'SATURDAY', startMinute: 9 * 60, endMinute: 24 * 60 },
      { dayOfWeek: 'SUNDAY', startMinute: 9 * 60, endMinute: 24 * 60 },
    ],
  },
  {
    id: 'clear',
    label: 'Clear all',
    title: 'Remove every window — the bot will be offline until you add one.',
    build: () => [],
  },
];

/**
 * Per-day, multi-window schedule editor.
 * <p>
 * Model: a flat list of {dayOfWeek, startMinute, endMinute}. Overnight windows must
 * be split (e.g. 22:00-24:00 Sun + 00:00-03:00 Mon) — the backend enforces start < end.
 * <p>
 * Friendliness affordances layered on top of the raw model:
 *   - Preset row at the top — 24/7, daytime, workweek, clear — so the common case
 *     doesn't require clicking through seven days.
 *   - Per-window "Copy to all days" propagates a single window's time range onto
 *     every day that doesn't already have it — handy once you've shaped one
 *     time range the way you want it. Non-destructive by design: existing
 *     windows on other days stay put; only this particular start/end pair gets
 *     added where missing. That lets the user build up a mixed schedule
 *     (e.g. morning + evening) by hitting the button on each window in turn.
 *   - A subtle text summary per day ("08:00–22:00") so the user can verify at
 *     a glance without expanding the inputs.
 */
export default function ScheduleEditor({ value, onChange }: Props) {
  const windowsByDay = groupByDay(value);

  const addWindow = (day: DayOfWeek) => {
    const next: ActiveWindow = { dayOfWeek: day, startMinute: 8 * 60, endMinute: 22 * 60 };
    onChange([...value, next]);
  };

  const removeWindow = (index: number) => {
    onChange(value.filter((_, i) => i !== index));
  };

  const updateWindow = (index: number, patch: Partial<ActiveWindow>) => {
    onChange(value.map((w, i) => (i === index ? { ...w, ...patch } : w)));
  };

  /**
   * Copy one specific window's {@code startMinute / endMinute} pair onto every
   * day of the week where that exact pair isn't already present. Non-destructive:
   * we do not delete the original window nor any other windows on other days.
   * Click it on a Monday morning window and you get the same morning window on
   * Tue–Sun too, without blowing away any evening windows those days already had.
   */
  const copyWindowToAll = (window: ActiveWindow) => {
    const { startMinute, endMinute } = window;
    const next: ActiveWindow[] = [...value];
    for (const d of DAYS_OF_WEEK) {
      const alreadyThere = next.some(
        (w) => w.dayOfWeek === d && w.startMinute === startMinute && w.endMinute === endMinute,
      );
      if (!alreadyThere) {
        next.push({ dayOfWeek: d, startMinute, endMinute });
      }
    }
    onChange(next);
  };

  return (
    <div className="schedule">
      <div className="schedule__presets">
        <span className="schedule__presets-label muted">Presets:</span>
        {PRESETS.map((p) => (
          <button
            key={p.id}
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={() => onChange(p.build())}
            title={p.title}
          >
            {p.label}
          </button>
        ))}
      </div>

      {DAYS_OF_WEEK.map((day) => {
        const bucket = windowsByDay[day];
        return (
          <div key={day} className="schedule__day">
            <div className="schedule__day-head">
              <span className="schedule__day-label">{DAY_LABELS[day]}</span>
              <span className="schedule__day-summary muted">{summarize(bucket)}</span>
              <div className="schedule__day-actions">
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => addWindow(day)}>
                  + Window
                </button>
              </div>
            </div>

            {bucket.length === 0 && (
              <p className="schedule__empty muted">Inactive all day.</p>
            )}

            {bucket.map(({ window, index }) => (
              <div key={index} className="schedule__window">
                <input
                  type="time"
                  value={minutesToTime(window.startMinute)}
                  onChange={(e) => updateWindow(index, { startMinute: timeToMinutes(e.target.value) })}
                />
                <span className="schedule__dash">–</span>
                <input
                  type="time"
                  value={minutesToTime(window.endMinute)}
                  onChange={(e) => updateWindow(index, { endMinute: timeToMinutes(e.target.value) })}
                />
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => copyWindowToAll(window)}
                  title="Add this same time range to every other day (keeps existing windows)."
                >
                  Copy to all days
                </button>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => removeWindow(index)}>
                  Remove
                </button>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

function groupByDay(windows: ActiveWindow[]): Record<DayOfWeek, DayBucket> {
  const grouped = Object.fromEntries(DAYS_OF_WEEK.map((d) => [d, [] as DayBucket])) as Record<
    DayOfWeek,
    DayBucket
  >;
  windows.forEach((w, i) => grouped[w.dayOfWeek].push({ window: w, index: i }));
  for (const day of DAYS_OF_WEEK) {
    grouped[day].sort((a, b) => a.window.startMinute - b.window.startMinute);
  }
  return grouped;
}

/**
 * Human-friendly one-liner for a day's windows — "08:00–22:00, 23:00–24:00"
 * or "Inactive all day". Shown next to the day label so the user doesn't
 * need to read the time inputs to get the gist.
 */
function summarize(bucket: DayBucket): string {
  if (bucket.length === 0) return 'Inactive all day';
  return bucket
    .map(({ window }) => `${minutesToTime(window.startMinute)}–${minutesToTime(window.endMinute)}`)
    .join(', ');
}
