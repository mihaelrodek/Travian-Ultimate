import { useEffect, useRef, useState } from 'react';
import { incomingApi } from '../api/incoming';
import { useGeneralSettings } from '../features/settings/GeneralSettingsContext';
import { useLiveEvent } from '../features/events/EventStreamContext';
import { playAlertSound } from '../utils/sound';
import type { IncomingAttack } from '../types/incoming';

const POLL_INTERVAL_MS = 30_000;

/**
 * App-wide watcher for incoming attacks on the active account (roadmap C3).
 * Polls the incoming list and:
 *   - exposes {@link incomingCount} (not-yet-landed waves) for the sidebar
 *     Defense badge, so there's a single poll instead of one per consumer;
 *   - fires a browser desktop notification for each newly-appeared upcoming
 *     attack, gated on the `desktopNotificationsEnabled` general setting.
 *
 * "Newly-appeared" is tracked against a per-account set of already-seen attack
 * ids. The very first poll after mount (or after switching account) seeds that
 * set WITHOUT notifying — otherwise every pre-existing attack would fire a
 * notification the moment the app loads.
 */
export function useIncomingAttackWatch(activeAccountId: number | null): { incomingCount: number } {
  const { settings } = useGeneralSettings();
  const desktopEnabled = settings?.desktopNotificationsEnabled ?? false;
  const soundEnabled = settings?.soundAlertsEnabled ?? false;

  const [incomingCount, setIncomingCount] = useState(0);

  // Ids we've already notified about, and whether this account has been seeded.
  const seenRef = useRef<Set<number>>(new Set());
  const seededRef = useRef(false);
  // Read the toggles through refs so flipping them doesn't tear down the poll.
  const desktopEnabledRef = useRef(desktopEnabled);
  desktopEnabledRef.current = desktopEnabled;
  const soundEnabledRef = useRef(soundEnabled);
  soundEnabledRef.current = soundEnabled;
  // Latest poll function, so the SSE handler can trigger an immediate refresh.
  const tickRef = useRef<(() => void) | null>(null);

  // Reset the dedup state whenever the active account changes.
  useEffect(() => {
    seenRef.current = new Set();
    seededRef.current = false;
  }, [activeAccountId]);

  // Ask for notification permission once, when the feature is switched on.
  useEffect(() => {
    if (!desktopEnabled) return;
    if (!('Notification' in window)) return;
    if (Notification.permission === 'default') {
      void Notification.requestPermission().catch(() => {
        /* denial is fine — we just won't post */
      });
    }
  }, [desktopEnabled]);

  useEffect(() => {
    if (activeAccountId == null) {
      setIncomingCount(0);
      return;
    }
    let cancelled = false;

    const tick = async () => {
      try {
        const rows = await incomingApi.list(activeAccountId);
        if (cancelled) return;
        const now = Date.now();
        const upcoming = rows.filter((r) => new Date(r.arrivalAt).getTime() > now);
        setIncomingCount(upcoming.length);

        const fresh = upcoming.filter((r) => !seenRef.current.has(r.id));
        upcoming.forEach((r) => seenRef.current.add(r.id));

        if (!seededRef.current) {
          // First successful poll for this account — establish the baseline,
          // don't notify for attacks that were already in progress.
          seededRef.current = true;
          return;
        }
        if (fresh.length > 0) {
          if (desktopEnabledRef.current) notifyIncoming(fresh);
          if (soundEnabledRef.current) playAlertSound();
        }
      } catch {
        if (!cancelled) setIncomingCount(0);
      }
    };

    // Expose the current tick to the SSE handler. On account change this effect
    // re-runs and re-points the ref; the old tick's `cancelled` flag makes any
    // late call a no-op.
    tickRef.current = tick;

    void tick();
    const id = window.setInterval(() => void tick(), POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      tickRef.current = null;
      window.clearInterval(id);
    };
  }, [activeAccountId]);

  // Live push (NOT4): refresh the moment the backend records a new incoming
  // attack for this account, instead of waiting out the poll interval. The
  // poll above stays as a fallback if the stream is down.
  useLiveEvent('incoming-attack', (data) => {
    const evt = data as { accountId?: number } | null;
    if (evt?.accountId === activeAccountId) void tickRef.current?.();
  });

  return { incomingCount };
}

/**
 * Post a desktop notification for one or more freshly-detected attacks. Silent
 * no-op unless the browser supports notifications and permission is granted.
 * A stable `tag` means a rapid second detection replaces the previous toast
 * instead of stacking.
 */
function notifyIncoming(fresh: IncomingAttack[]): void {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;

  const count = fresh.length;
  const first = fresh[0];
  const landsAt = new Date(first.arrivalAt).toLocaleTimeString();
  const attacker = first.attackerName ?? 'Unknown';

  const title = count === 1 ? 'Incoming attack' : `${count} incoming attacks`;
  const body =
    count === 1
      ? `From ${attacker} — lands ${landsAt}`
      : `${count} new attacks detected — first from ${attacker} at ${landsAt}`;

  try {
    // eslint-disable-next-line no-new
    new Notification(title, { body, tag: 'travian-incoming-attack' });
  } catch {
    /* Some browsers throw if constructed outside a SW in certain contexts — ignore. */
  }
}
