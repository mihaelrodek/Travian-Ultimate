import { useCallback, useEffect, useMemo, useState } from 'react';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { heroApi } from '../api/hero';
import { accountSettingsApi } from '../api/settings';
import { ApiException } from '../types/api';
import {
  HERO_ATTRIBUTES,
  type AccountSettings,
  type AccountSettingsUpdate,
  type HeroAttribute,
} from '../types/settings';
import type { AdventureDifficulty, Hero } from '../types/hero';

/**
 * Hero dashboard for the currently-scoped account.
 *
 * Sections:
 *  - Status card — just Health and Experience plus a Scrape button. We
 *    deliberately cut the older attributes / inventory / full-stat grid:
 *    those lived here only because the data was available, not because the
 *    user actually needed them on this screen.
 *  - Hero settings — auto-level attribute, auto-heal threshold, auto-adventure
 *    toggle, and min health for adventure. Moved here from the Account Settings
 *    page so the whole "hero policy" story sits next to the live hero stats.
 *  - Adventures list.
 *
 * Data flow: a single {@code /api/accounts/{id}/hero} call loads the hero; a
 * parallel {@code /api/accounts/{id}/settings} call loads the hero policy.
 * Scrape now re-fetches the hero (not the settings — they're user-owned).
 * On first run the GET returns 404, and the UI shows an empty state pointing
 * at Scrape now.
 */
export default function HeroPage() {
  const { accountId } = useCurrentAccount();

  const [hero, setHero] = useState<Hero | null>(null);
  const [loading, setLoading] = useState(false);
  const [scraping, setScraping] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [firstRun, setFirstRun] = useState(false);

  const reload = useCallback(async () => {
    if (accountId == null) return;
    setLoading(true);
    setError(null);
    try {
      const res = await heroApi.get(accountId);
      setHero(res);
      setFirstRun(false);
    } catch (err) {
      if (err instanceof ApiException && err.status === 404) {
        setHero(null);
        setFirstRun(true);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load hero');
      }
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  useEffect(() => {
    if (accountId == null) {
      setHero(null);
      setFirstRun(false);
      return;
    }
    void reload();
  }, [accountId, reload]);

  const scrape = async () => {
    if (accountId == null) return;
    setScraping(true);
    setError(null);
    try {
      const res = await heroApi.scrape(accountId);
      setHero(res);
      setFirstRun(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Scrape failed');
    } finally {
      setScraping(false);
    }
  };

  return (
    <div className="stack">
      {accountId == null ? (
        <Card>
          <p className="muted">Pick an account on the Accounts page to see the hero.</p>
        </Card>
      ) : firstRun ? (
        <Card title="No hero data yet">
          <p className="muted" style={{ marginBottom: '0.75rem' }}>
            The hero hasn't been scraped for this account yet. Make sure the
            account is logged in, then click Scrape now.
          </p>
          <button
            type="button"
            className="btn btn--primary"
            onClick={scrape}
            disabled={scraping}
          >
            {scraping ? 'Scraping…' : 'Scrape now'}
          </button>
          {error && <p className="form__msg form__msg--error" style={{ marginTop: '0.5rem' }}>{error}</p>}
        </Card>
      ) : hero == null ? (
        <Card>
          <p className="muted">{loading ? 'Loading…' : 'No hero data.'}</p>
        </Card>
      ) : (
        <>
          <StatusCard
            hero={hero}
            scraping={scraping}
            loading={loading}
            error={error}
            onScrape={scrape}
            onReload={() => void reload()}
          />
          <HeroSettingsCard accountId={accountId} />
          <AdventuresCard hero={hero} />
        </>
      )}
    </div>
  );
}

// ---------------- Status ----------------

function StatusCard({
  hero,
  scraping,
  loading,
  error,
  onScrape,
  onReload,
}: {
  hero: Hero;
  scraping: boolean;
  loading: boolean;
  error: string | null;
  onScrape: () => void;
  onReload: () => void;
}) {
  const xpPct = useMemo(() => {
    if (!hero.experienceForNext || hero.experienceForNext <= 0) return null;
    return Math.max(0, Math.min(100, Math.round((hero.experience / hero.experienceForNext) * 100)));
  }, [hero.experience, hero.experienceForNext]);

  return (
    <Card title="Status">
      <div className="toolbar" style={{ gap: '0.75rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button type="button" className="btn btn--primary" onClick={onScrape} disabled={scraping}>
          {scraping ? 'Scraping…' : 'Scrape now'}
        </button>
        <button type="button" className="btn btn--ghost" onClick={onReload} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
        <span className="muted" style={{ flex: 1 }}>
          {hero.lastScrapedAt
            ? `Last scraped: ${new Date(hero.lastScrapedAt).toLocaleString()}`
            : 'Never scraped.'}
        </span>
      </div>
      {error && <p className="form__msg form__msg--error" style={{ marginBottom: '0.5rem' }}>{error}</p>}

      <div className="hero-grid">
        <Stat label="Health" value={`${hero.health}%`} />
        <Stat
          label="Experience"
          value={
            hero.experienceForNext
              ? `${hero.experience.toLocaleString()} / ${hero.experienceForNext.toLocaleString()}`
              : hero.experience.toLocaleString()
          }
        />
      </div>

      <div style={{ marginTop: '0.75rem' }}>
        <ProgressBar label={`Health ${hero.health}%`} pct={hero.health} tone={healthTone(hero.health)} />
      </div>
      {xpPct != null && (
        <div style={{ marginTop: '0.5rem' }}>
          <ProgressBar label={`XP ${xpPct}%`} pct={xpPct} tone="accent" />
        </div>
      )}
    </Card>
  );
}

function Stat({
  label,
  value,
  emphasis,
}: {
  label: string;
  value: string | number;
  emphasis?: boolean;
}) {
  return (
    <div className="hero-stat">
      <div className="hero-stat__label">{label}</div>
      <div className={`hero-stat__value${emphasis ? ' hero-stat__value--emphasis' : ''}`}>{value}</div>
    </div>
  );
}

function ProgressBar({ label, pct, tone }: { label: string; pct: number; tone: 'accent' | 'ok' | 'warn' | 'danger' }) {
  const clamped = Math.max(0, Math.min(100, pct));
  return (
    <div>
      <div className="muted" style={{ fontSize: '0.8rem', marginBottom: '0.2rem' }}>{label}</div>
      <div className={`progress progress--${tone}`}>
        <div className="progress__fill" style={{ width: `${clamped}%` }} />
      </div>
    </div>
  );
}

function healthTone(health: number): 'ok' | 'warn' | 'danger' {
  if (health >= 70) return 'ok';
  if (health >= 35) return 'warn';
  return 'danger';
}

// ---------------- Hero settings ----------------

type HeroSettingsDraft = Pick<
  AccountSettings,
  'heroAutoLevel' | 'heroHealThresholdPct' | 'heroAutoAdventure' | 'heroMinHealthForAdventure'
>;
type SettingsLoad = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; message: string };
type SettingsSave =
  | { kind: 'idle' }
  | { kind: 'saving' }
  | { kind: 'saved' }
  | { kind: 'error'; message: string };

/**
 * Hero policy editor — auto-level attribute, heal threshold, auto-adventure
 * toggle, and min-health-for-adventure. These live in the account-wide
 * {@link AccountSettings} blob on the backend but are edited here, next to
 * the live hero stats, rather than buried in the Account Settings page.
 * <p>
 * Saving PUTs the full settings object. To avoid clobbering unrelated fields
 * (alerts, NPC, proxy, schedule), we load the whole blob first, merge the
 * four hero fields into the draft on save, and send the merged body.
 */
function HeroSettingsCard({ accountId }: { accountId: number }) {
  const [full, setFull] = useState<AccountSettings | null>(null);
  const [draft, setDraft] = useState<HeroSettingsDraft | null>(null);
  const [load, setLoad] = useState<SettingsLoad>({ kind: 'loading' });
  const [save, setSave] = useState<SettingsSave>({ kind: 'idle' });

  useEffect(() => {
    let cancelled = false;
    setLoad({ kind: 'loading' });
    accountSettingsApi
      .get(accountId)
      .then((s) => {
        if (cancelled) return;
        setFull(s);
        setDraft({
          heroAutoLevel: s.heroAutoLevel,
          heroHealThresholdPct: s.heroHealThresholdPct,
          heroAutoAdventure: s.heroAutoAdventure,
          heroMinHealthForAdventure: s.heroMinHealthForAdventure,
        });
        setLoad({ kind: 'ready' });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad({
          kind: 'error',
          message: err instanceof Error ? err.message : 'Failed to load hero settings',
        });
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  if (load.kind === 'loading') {
    return <Card title="Hero settings"><p className="muted">Loading…</p></Card>;
  }
  if (load.kind === 'error') {
    return (
      <Card title="Hero settings">
        <p className="form__msg form__msg--error">{load.message}</p>
      </Card>
    );
  }
  if (!draft || !full) return null;

  const patch = <K extends keyof HeroSettingsDraft>(key: K, value: HeroSettingsDraft[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const submit = async () => {
    if (!draft || !full) return;
    setSave({ kind: 'saving' });
    // Merge hero fields into the full settings blob — the PUT is whole-object,
    // not patch, so we must preserve the fields we don't own here.
    const body: AccountSettingsUpdate = {
      tribe: full.tribe,
      heroAutoLevel: draft.heroAutoLevel,
      heroHealThresholdPct: draft.heroHealThresholdPct,
      heroAutoAdventure: draft.heroAutoAdventure,
      heroMinHealthForAdventure: draft.heroMinHealthForAdventure,
      alertOnAttack: full.alertOnAttack,
      alertIncludeTroopCounts: full.alertIncludeTroopCounts,
      alertChannels: full.alertChannels,
      proxyUrl: full.proxyUrl,
      proxyUsername: full.proxyUsername,
      // null => leave server-side password alone
      proxyPassword: null,
      activeWindows: full.activeWindows,
    };
    try {
      const saved = await accountSettingsApi.update(accountId, body);
      setFull(saved);
      setDraft({
        heroAutoLevel: saved.heroAutoLevel,
        heroHealThresholdPct: saved.heroHealThresholdPct,
        heroAutoAdventure: saved.heroAutoAdventure,
        heroMinHealthForAdventure: saved.heroMinHealthForAdventure,
      });
      setSave({ kind: 'saved' });
    } catch (err) {
      setSave({
        kind: 'error',
        message: err instanceof Error ? err.message : 'Save failed',
      });
    }
  };

  return (
    <Card title="Hero settings" hint="Auto-level / auto-heal / auto-adventure policy.">
      <div className="form form--compact">
        <div className="form__field">
          <label>Auto-level attribute</label>
          <select
            value={draft.heroAutoLevel}
            onChange={(e) => patch('heroAutoLevel', e.target.value as HeroAttribute)}
          >
            {HERO_ATTRIBUTES.map((a) => (
              <option key={a} value={a}>{humanize(a)}</option>
            ))}
          </select>
        </div>

        <div className="form__field">
          <label>Auto-heal below (%)</label>
          <input
            type="number"
            min={0}
            max={100}
            value={draft.heroHealThresholdPct}
            onChange={(e) => patch('heroHealThresholdPct', clampInt(e.target.value, 0, 100))}
          />
        </div>

        <label className="form__check form__field--full">
          <input
            type="checkbox"
            checked={draft.heroAutoAdventure}
            onChange={(e) => patch('heroAutoAdventure', e.target.checked)}
          />
          <span>Auto-send hero on adventures</span>
        </label>

        <div className="form__field">
          <label>Min health for adventure (%)</label>
          <input
            type="number"
            min={0}
            max={100}
            value={draft.heroMinHealthForAdventure}
            onChange={(e) => patch('heroMinHealthForAdventure', clampInt(e.target.value, 0, 100))}
          />
        </div>
      </div>

      <div className="settings-savebar" style={{ marginTop: '0.75rem' }}>
        <div className="settings-savebar__msg">
          {save.kind === 'saving' && <span className="muted">Saving…</span>}
          {save.kind === 'saved' && <span className="pill pill--success">Saved</span>}
          {save.kind === 'error' && (
            <span className="form__msg form__msg--error" style={{ margin: 0 }}>
              {save.message}
            </span>
          )}
        </div>
        <button type="button" className="btn btn--primary" onClick={submit} disabled={save.kind === 'saving'}>
          Save
        </button>
      </div>
    </Card>
  );
}

function clampInt(raw: string, min: number, max: number): number {
  const n = Number.parseInt(raw, 10);
  if (Number.isNaN(n)) return min;
  return Math.max(min, Math.min(max, n));
}

function humanize(value: string): string {
  return value
    .split('_')
    .map((s) => s.charAt(0) + s.slice(1).toLowerCase())
    .join(' ');
}

// ---------------- Adventures ----------------

function AdventuresCard({ hero }: { hero: Hero }) {
  if (hero.adventures.length === 0) {
    return (
      <Card title="Adventures">
        <p className="muted">No adventures available right now.</p>
      </Card>
    );
  }
  return (
    <Card title={`Adventures (${hero.adventures.length})`}>
      <table className="table">
        <thead>
          <tr>
            <th>Coords</th>
            <th>Difficulty</th>
            <th>Expires</th>
          </tr>
        </thead>
        <tbody>
          {hero.adventures.map((a) => (
            <tr key={a.id}>
              <td>
                ({a.targetX}|{a.targetY})
              </td>
              <td>{formatDifficulty(a.difficulty)}</td>
              <td>{a.endAt ? new Date(a.endAt).toLocaleString() : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

// ---------------- Formatters ----------------

function formatDifficulty(d: AdventureDifficulty): string {
  switch (d) {
    case 'NORMAL': return 'Normal';
    case 'HARD': return 'Hard';
    default: return 'Unknown';
  }
}
