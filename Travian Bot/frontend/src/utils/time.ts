/**
 * Shared time helpers for rendering scraped timestamps in the UI.
 * <p>
 * The backend stores every {@code finishesAt} in UTC (Instant). The Travian
 * server itself runs in its own timezone and displays countdowns in that zone,
 * e.g. "done at 09:05". The user's machine clock is a third zone — e.g. a
 * player in CET running a bot pointed at a UTC+02 server will see their
 * wall-clock differ from the server's by one hour.
 * <p>
 * We surface both: "server clock" (what the Travian tab shows) and "local
 * clock" (the user's wall clock). If we don't yet know the server offset,
 * we fall back to local-only.
 */

/**
 * Format an ISO-8601 Instant as HH:MM using the server's UTC offset.
 *
 * @param iso ISO timestamp in UTC, e.g. "2026-04-18T09:05:12Z"
 * @param offsetMinutes the server's offset from UTC in minutes (e.g. 60 for UTC+01:00),
 *        or null when we haven't scraped it yet
 * @returns "HH:MM" in the server's local wall clock, or null if inputs are missing
 */
export function formatServerClock(iso: string | null, offsetMinutes: number | null): string | null {
  if (!iso) return null;
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return null;
  if (offsetMinutes == null) return null;
  const shifted = new Date(ms + offsetMinutes * 60_000);
  // Extract UTC hours/minutes from the shifted instant — the shift has already
  // moved the timestamp into the server's wall-clock frame, so reading UTC
  // components gives the server's local HH:MM.
  const hh = String(shifted.getUTCHours()).padStart(2, '0');
  const mm = String(shifted.getUTCMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

/**
 * Format an ISO-8601 Instant as HH:MM in the user's local wall clock.
 */
export function formatLocalClock(iso: string | null): string | null {
  if (!iso) return null;
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return null;
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

/**
 * Format remaining time from now to a future instant as "h:mm:ss", or
 * "m:ss" when less than an hour. Returns null for past or invalid times.
 */
export function formatRemaining(iso: string | null, now: number = Date.now()): string | null {
  if (!iso) return null;
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return null;
  let remaining = Math.floor((ms - now) / 1000);
  if (remaining <= 0) return null;
  const h = Math.floor(remaining / 3600);
  remaining -= h * 3600;
  const m = Math.floor(remaining / 60);
  const s = remaining - m * 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Compose the best available finish-time label for a queue row. Prefers the
 * server clock, falls back to local clock when the offset is unknown.
 * The remaining "in Xm" suffix comes from the same instant so both parts stay
 * consistent even if the user's OS clock is a few seconds off from the bot's.
 */
export function formatFinishLabel(iso: string | null, offsetMinutes: number | null): string | null {
  if (!iso) return null;
  const server = formatServerClock(iso, offsetMinutes);
  const local = formatLocalClock(iso);
  const remaining = formatRemaining(iso);
  const clock = server ?? local;
  if (!clock) return null;
  const base = server ? `done at ${server}` : `done at ${local} (local)`;
  return remaining ? `${base} · in ${remaining}` : base;
}
