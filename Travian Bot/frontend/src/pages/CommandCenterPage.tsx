import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { villagesApi } from '../api/villages';
import { farmlistsApi } from '../api/farmlists';
import { incomingApi } from '../api/incoming';
import { botLogApi } from '../api/botLog';
import type { Village } from '../types/village';
import type { Farmlist } from '../types/farmlist';
import type { IncomingAttack } from '../types/incoming';
import type { BotEvent } from '../types/botLog';

/**
 * GlassHUD command center — the new landing page.
 *
 * Four stat tiles up top, threat ring + farm-performance chart in the
 * middle row, then a simple insights pair (active villages roster + bot
 * activity feed) at the bottom. Everything ticks once per second so the
 * countdown clocks stay live.
 *
 * Data sources are all already exposed by the backend — the page is a
 * read-only aggregator, no new API surface. The chart is synthetic-from-
 * raw-data: we don't yet have a daily rollup, so it visualises last 14
 * days of bot events as activity bars. When the raid-report rollup ships
 * this chart swaps in for a real loot curve.
 */
export default function CommandCenterPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [villages, setVillages] = useState<Village[]>([]);
  const [farmlists, setFarmlists] = useState<Farmlist[]>([]);
  const [incoming, setIncoming] = useState<IncomingAttack[]>([]);
  const [events, setEvents] = useState<BotEvent[]>([]);
  const [now, setNow] = useState<number>(() => Date.now());
  const [loadError, setLoadError] = useState<string | null>(null);

  // Live clock + countdown tick.
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (accountId == null) {
      setVillages([]);
      setFarmlists([]);
      setIncoming([]);
      setEvents([]);
      return;
    }
    (async () => {
      try {
        const [v, f, i, e] = await Promise.all([
          villagesApi.list(accountId),
          farmlistsApi.list(accountId),
          incomingApi.list(accountId),
          botLogApi.list({ accountId, page: 0, size: 20 }),
        ]);
        if (cancelled) return;
        setVillages(v);
        setFarmlists(f);
        setIncoming(i);
        setEvents(e.items);
        setLoadError(null);
      } catch (err) {
        if (cancelled) return;
        setLoadError(
          err instanceof Error ? err.message : 'Failed to load dashboard data',
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  const totalPopulation = useMemo(
    () => villages.reduce((acc, v) => acc + (v.population ?? 0), 0),
    [villages],
  );

  const capitalVillage = useMemo(
    () => villages.find((v) => v.capital) ?? null,
    [villages],
  );

  // Soonest upcoming wave — the threat ring + INCOMING tile both read off this.
  const nearestImpact = useMemo(() => {
    const upcoming = incoming
      .filter((a) => new Date(a.arrivalAt).getTime() > now)
      .sort(
        (a, b) =>
          new Date(a.arrivalAt).getTime() - new Date(b.arrivalAt).getTime(),
      );
    return upcoming[0] ?? null;
  }, [incoming, now]);

  const incomingCount = useMemo(
    () =>
      incoming.filter((a) => new Date(a.arrivalAt).getTime() > now).length,
    [incoming, now],
  );

  // Synthesise a 14-day bar series from bot events — placeholder until the
  // raid-report rollup feeds a real loot curve in. Bucket count keyed on
  // local-day to make the visual deterministic across page refreshes.
  const chartSeries = useMemo(() => synthSeries(events, now), [events, now]);

  const clock = useMemo(() => formatClock(now), [now]);

  if (accountId == null && !accountsLoading) {
    return (
      <div className="stack">
        <header className="gh-header">
          <div>
            <div className="gh-header__title">Naredbeni centar</div>
            <div className="gh-header__sub">no account selected</div>
          </div>
          <div className="gh-header__right">
            <span className="gh-pill gh-pill--clock">{clock}</span>
          </div>
        </header>
        <div className="card" style={{ marginTop: '1rem' }}>
          <p className="muted">
            Pick an account from the{' '}
            <Link to="/accounts" className="link">
              Accounts page
            </Link>{' '}
            to load the command center.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="stack" style={{ gap: '0' }}>
      <header className="gh-header">
        <div>
          <div className="gh-header__title">Naredbeni centar</div>
          <div className="gh-header__sub">
            {villages.length} villages · synced now
          </div>
        </div>
        <div className="gh-header__right">
          <span className="gh-pill gh-pill--clock">{clock}</span>
          <span className="gh-pill">QUEUE · {pendingQueueCount(villages)}</span>
        </div>
      </header>

      <div style={{ padding: '18px 0' }}>
        {loadError && (
          <p
            className="form__msg form__msg--error"
            style={{ marginBottom: '0.75rem' }}
          >
            {loadError}
          </p>
        )}

        {/* ---- Row 1: four stat tiles ---- */}
        <div className="gh-stat-grid">
          <StatTile
            label="POPULATION"
            value={totalPopulation.toLocaleString()}
            valueClass="gh-stat__value--gradient"
            delta={
              capitalVillage
                ? `cap · ${capitalVillage.name}`
                : `${villages.length} villages tracked`
            }
            deltaClass=""
          />
          <StatTile
            label="VILLAGES"
            value={String(villages.length)}
            delta={
              capitalVillage
                ? `(${capitalVillage.coordinateX}|${capitalVillage.coordinateY})`
                : 'no capital set'
            }
            deltaClass=""
          />
          <StatTile
            label="FARMLISTS"
            value={String(farmlists.length)}
            delta={`${farmlists.filter((f) => f.enabled !== false).length} active`}
            deltaClass="gh-stat__delta--good"
          />
          <StatTile
            label="INCOMING"
            value={String(incomingCount)}
            valueClass="gh-stat__value--danger"
            delta={
              nearestImpact
                ? formatDuration(
                    new Date(nearestImpact.arrivalAt).getTime() - now,
                  )
                : 'all clear'
            }
            deltaClass={
              nearestImpact ? 'gh-stat__delta--danger' : 'gh-stat__delta--good'
            }
            variant="danger"
          />
        </div>

        {/* ---- Row 2: threat ring + farm-perf chart ---- */}
        <div className="gh-row gh-row--threat">
          <ThreatRingCard nearest={nearestImpact} now={now} />
          <ChartCard series={chartSeries} />
        </div>

        {/* ---- Row 3: villages roster (compact) + activity feed ---- */}
        <div className="gh-row gh-row--feed">
          <VillagesRosterCard villages={villages} />
          <ActivityFeedCard events={events} />
        </div>
      </div>
    </div>
  );
}

// ===========================================================================
//                              Stat tile
// ===========================================================================

function StatTile({
  label,
  value,
  delta,
  variant,
  valueClass = '',
  deltaClass = '',
}: {
  label: string;
  value: string;
  delta: string;
  variant?: 'danger';
  valueClass?: string;
  deltaClass?: string;
}) {
  const cls = variant === 'danger' ? 'gh-stat gh-stat--danger' : 'gh-stat';
  return (
    <div className={cls}>
      <div className="gh-stat__label">{label}</div>
      <div className={`gh-stat__value ${valueClass}`.trim()}>{value}</div>
      <div className={`gh-stat__delta ${deltaClass}`.trim()}>{delta}</div>
    </div>
  );
}

// ===========================================================================
//                              Threat ring
// ===========================================================================

const RING_CIRC = 326.7; // 2πr at r=52

function ThreatRingCard({
  nearest,
  now,
}: {
  nearest: IncomingAttack | null;
  now: number;
}) {
  const remainingMs = nearest
    ? Math.max(0, new Date(nearest.arrivalAt).getTime() - now)
    : 0;
  // Ring fills as the attack approaches. Anchored at a 1h window — anything
  // longer reads as "fresh", and anything shorter rapidly fills the ring red.
  const frac = Math.max(0, Math.min(1, remainingMs / (60 * 60 * 1000)));
  const dashOffset = (RING_CIRC * (1 - frac)).toFixed(1);

  return (
    <div className="gh-threat-card">
      <div className="gh-threat-card__title">Threat · Incoming</div>
      <div className="gh-threat-ring">
        <svg width="170" height="170" viewBox="0 0 120 120">
          <circle
            cx="60"
            cy="60"
            r="52"
            fill="none"
            stroke="rgba(255,255,255,.08)"
            strokeWidth="9"
          />
          <circle
            cx="60"
            cy="60"
            r="52"
            fill="none"
            stroke="url(#ghg)"
            strokeWidth="9"
            strokeLinecap="round"
            strokeDasharray={String(RING_CIRC)}
            strokeDashoffset={dashOffset}
            transform="rotate(-90 60 60)"
          />
          <defs>
            <linearGradient id="ghg" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0" stopColor="#ff5f9e" />
              <stop offset="1" stopColor="#ff8a5f" />
            </linearGradient>
          </defs>
        </svg>
        <div className="gh-threat-ring__inner">
          <span className="gh-threat-ring__time">
            {nearest ? formatDuration(remainingMs) : '--:--:--'}
          </span>
          <span className="gh-threat-ring__caption">TO IMPACT</span>
        </div>
      </div>
      <div className="gh-threat-card__village">
        {nearest?.sourceVillageName || nearest?.attackerName || 'No threats'}
      </div>
      <div className="gh-threat-card__meta">
        {nearest
          ? `(${nearest.sourceX ?? '?'}|${nearest.sourceY ?? '?'}) · ${nearest.waveCount ?? 1} wave${nearest.waveCount === 1 ? '' : 's'}`
          : 'all clear'}
      </div>
    </div>
  );
}

// ===========================================================================
//                              Chart card
// ===========================================================================

interface ChartSeries {
  daily: number[]; // length 14
  max: number;
}

function ChartCard({ series }: { series: ChartSeries }) {
  const W = 680;
  const H = 190;
  const pad = 16;
  const n = series.daily.length;
  const max = Math.max(1, series.max);
  const X = (i: number) => (i / (n - 1)) * W;
  const Y = (v: number) => H - pad - (v / max) * (H - pad * 2);

  let line = '';
  let area = `M0 ${Y(series.daily[0]).toFixed(1)}`;
  series.daily.forEach((v, i) => {
    const x = X(i).toFixed(1);
    const y = Y(v).toFixed(1);
    line += (i ? 'L' : 'M') + x + ' ' + y + ' ';
    area += ' L' + x + ' ' + y;
  });
  area += ` L${W} ${H} L0 ${H} Z`;

  return (
    <div className="gh-chart-card">
      <div className="gh-chart-card__head">
        <span className="gh-chart-card__title">ACTIVITY · LAST 14 DAYS</span>
        <span className="gh-chart-card__hint">{series.max} max events/day</span>
      </div>
      <svg
        className="gh-chart-card__svg"
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
      >
        <path d={area} fill="url(#gha)" stroke="none" />
        <path
          d={line}
          fill="none"
          stroke="#36e6ff"
          strokeWidth="2.4"
          strokeLinejoin="round"
          vectorEffect="non-scaling-stroke"
        />
        <defs>
          <linearGradient id="gha" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="rgba(54,230,255,.28)" />
            <stop offset="1" stopColor="rgba(54,230,255,0)" />
          </linearGradient>
        </defs>
      </svg>
    </div>
  );
}

// Build a 14-day daily-count bucket from bot events. Today is day 13 (last
// column); day 0 is 13 days ago. Events outside the window are ignored.
function synthSeries(events: BotEvent[], now: number): ChartSeries {
  const buckets = new Array(14).fill(0) as number[];
  const dayMs = 24 * 60 * 60 * 1000;
  const today = startOfDay(now);
  for (const e of events) {
    const d = startOfDay(new Date(e.createdAt).getTime());
    const idx = 13 - Math.round((today - d) / dayMs);
    if (idx >= 0 && idx < 14) buckets[idx] += 1;
  }
  return { daily: buckets, max: Math.max(...buckets, 0) };
}

function startOfDay(ts: number): number {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

// ===========================================================================
//                              Villages roster
// ===========================================================================

/**
 * Tight compact list of the top villages by population — fills the spot
 * the alliance-rank card holds in the source design. Real alliance ranks
 * need a serverId resolution step the dashboard MVP skips; this is the
 * higher-signal substitute for now.
 */
function VillagesRosterCard({ villages }: { villages: Village[] }) {
  const top = useMemo(
    () =>
      [...villages]
        .sort((a, b) => (b.population ?? 0) - (a.population ?? 0))
        .slice(0, 5),
    [villages],
  );

  return (
    <div className="card">
      <h2 className="card__title">VILLAGES · TOP 5</h2>
      <div className="gh-rank-list">
        {top.length === 0 && (
          <div className="muted" style={{ fontSize: '12px' }}>
            No villages tracked yet.
          </div>
        )}
        {top.map((v, idx) => (
          <div
            key={v.id}
            className={`gh-rank-row ${v.capital ? 'gh-rank-row--you' : ''}`}
          >
            <span>
              #{idx + 1} {v.name}
              {v.capital && (
                <span className="muted" style={{ marginLeft: '0.4rem' }}>
                  cap
                </span>
              )}
            </span>
            <span className="gh-rank-row__pop">
              {(v.population ?? 0).toLocaleString()}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ===========================================================================
//                              Activity feed
// ===========================================================================

function ActivityFeedCard({ events }: { events: BotEvent[] }) {
  const top = events.slice(0, 6);
  return (
    <div className="card">
      <h2 className="card__title">ACTIVITY</h2>
      <div style={{ marginTop: '8px' }}>
        {top.length === 0 && (
          <div className="muted" style={{ fontSize: '12px' }}>
            No bot events yet.
          </div>
        )}
        {top.map((e) => (
          <div key={e.id} className="gh-feed-row">
            <span className="gh-feed-row__time">{formatHourMin(e.createdAt)}</span>
            <span
              className="gh-feed-row__dot"
              style={{
                background: outcomeColor(e.outcome),
                boxShadow: `0 0 8px ${outcomeColor(e.outcome)}`,
              }}
            />
            <span className="gh-feed-row__msg" title={e.message ?? e.action}>
              <strong style={{ color: 'var(--text)' }}>{e.action}</strong>
              {e.message ? ` · ${e.message}` : ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

function outcomeColor(o: string): string {
  switch (o) {
    case 'OK':
      return '#3fe0a0';
    case 'WARN':
      return '#f5c94f';
    case 'ERROR':
      return '#ff5f9e';
    default:
      return '#a87bff';
  }
}

// ===========================================================================
//                              Helpers
// ===========================================================================

function pendingQueueCount(villages: Village[]): number {
  return villages.reduce(
    (acc, v) =>
      acc + (v.queue?.filter((q) => q.status === 'PENDING').length ?? 0),
    0,
  );
}

function formatClock(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function formatDuration(ms: number): string {
  if (ms < 0) ms = 0;
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const x = s % 60;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(h)}:${p(m)}:${p(x)}`;
}

function formatHourMin(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}
