/** Convert minutes-since-midnight (0..1440) to an "HH:MM" string for <input type="time">. */
export function minutesToTime(m: number): string {
  const clamped = Math.max(0, Math.min(1440, m | 0));
  const h = Math.floor(clamped / 60);
  const min = clamped % 60;
  return `${h.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}`;
}

/** Parse "HH:MM" (or "HH:MM:SS") into minutes-since-midnight. Returns 0 for bad input. */
export function timeToMinutes(t: string): number {
  if (!t) return 0;
  const [h, m] = t.split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return 0;
  return Math.max(0, Math.min(1440, h * 60 + m));
}
