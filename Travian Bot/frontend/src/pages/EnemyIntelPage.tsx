import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import StatusBadge from '../components/StatusBadge';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { enemyIntelApi } from '../api/enemyintel';
import type {
  EnemyPlayer,
  EnemyPlayerDetail,
} from '../types/enemyintel';

/**
 * Enemy intel page for the currently-scoped account.
 *
 * Layout:
 *   - Top: action toolbar (register from recent attacks, refresh stale, refresh
 *     local list).
 *   - Middle: table of tracked enemies. Clicking a row opens the right-hand
 *     side panel with the timeline (last 20 snapshots) for that enemy.
 *   - Right panel: detail view + Force-scrape button + timeline. Hero level
 *     changes are colour-coded (red = drop, blue = level up).
 *
 * Mirrors the patterns from {@code IncomingAttacksPage} (action toolbar +
 * background refresh + stale-fetch guard) and {@code VillagesListPage}
 * (master-detail layout, click-row-to-show-detail).
 *
 * Static copy makes the "we don't auto-do anything dangerous here" model
 * obvious — the user clicks Force scrape to make HTTP traffic happen, the
 * scheduler does the periodic work in the background.
 */
export default function EnemyIntelPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [enemies, setEnemies] = useState<EnemyPlayer[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  // Action button busy state — only one button runs at a time. Keeps
  // accidental double-clicks from kicking off duplicate scrapes.
  const [busy, setBusy] = useState<'register' | 'refresh' | null>(null);

  // Selected enemy id + lazily-loaded detail. The detail panel renders the
  // last 20 snapshots; we refetch on every selection so the timeline stays
  // current after a force-scrape.
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<EnemyPlayerDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [scrapingOne, setScrapingOne] = useState(false);

  // Stale-account guard — same pattern as IncomingAttacksPage. If the user
  // flips to another account mid-fetch, drop the response on the floor.
  const accountRef = useRef<number | null>(accountId);
  accountRef.current = accountId;

  const reload = useCallback(async () => {
    if (accountId == null) {
      setEnemies([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const list = await enemyIntelApi.listEnemies(accountId);
      if (accountRef.current !== accountId) return;
      setEnemies(list);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load enemies');
    } finally {
      if (accountRef.current === accountId) setLoading(false);
    }
  }, [accountId]);

  useEffect(() => { void reload(); }, [reload]);

  // When selectedId changes (or after a force-scrape) re-pull the detail.
  useEffect(() => {
    if (accountId == null || selectedId == null) {
      setDetail(null);
      setDetailError(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    enemyIntelApi.getEnemy(accountId, selectedId)
      .then((d) => { if (!cancelled) { setDetail(d); setDetailError(null); } })
      .catch((err) => {
        if (cancelled) return;
        setDetailError(err instanceof Error ? err.message : 'Failed to load detail');
        setDetail(null);
      })
      .finally(() => { if (!cancelled) setDetailLoading(false); });
    return () => { cancelled = true; };
  }, [accountId, selectedId]);

  const onRegister = async () => {
    if (accountId == null) return;
    setBusy('register');
    setFlash(null);
    try {
      const res = await enemyIntelApi.registerFromRecentAttacks(accountId, 48);
      setFlash({
        kind: 'ok',
        text: res.created > 0
          ? `Registered ${res.created} new enemy${res.created === 1 ? '' : 'ies'} from recent attacks.`
          : 'No new enemies — recent attacks were already on the watchlist.',
      });
      await reload();
    } catch (err) {
      setFlash({ kind: 'err', text: err instanceof Error ? err.message : 'Register failed' });
    } finally {
      setBusy(null);
    }
  };

  const onRefreshStale = async () => {
    if (accountId == null) return;
    setBusy('refresh');
    setFlash(null);
    try {
      const res = await enemyIntelApi.refreshStale(accountId, 6, 10);
      setFlash({
        kind: 'ok',
        text: res.scraped > 0
          ? `Refreshed ${res.scraped} stale enemy profile${res.scraped === 1 ? '' : 's'}.`
          : 'Nothing stale to refresh.',
      });
      await reload();
      // If the user is looking at a detail panel, reload that too — its
      // timeline may have gained a new entry.
      if (selectedId != null) {
        const d = await enemyIntelApi.getEnemy(accountId, selectedId);
        if (accountRef.current === accountId) setDetail(d);
      }
    } catch (err) {
      setFlash({ kind: 'err', text: err instanceof Error ? err.message : 'Refresh failed' });
    } finally {
      setBusy(null);
    }
  };

  const onForceScrape = async () => {
    if (accountId == null || selectedId == null) return;
    setScrapingOne(true);
    setFlash(null);
    try {
      const snap = await enemyIntelApi.forceScrapeEnemy(accountId, selectedId);
      setFlash({
        kind: 'ok',
        text: snap == null
          ? 'Scrape finished, but the profile page returned no parseable fields.'
          : `Scrape finished — snapshot ${snap.id} saved.`,
      });
      // Reload both detail panel and master list — the summary row's
      // lastScrapedAt and tracked fields will have moved.
      const [d] = await Promise.all([
        enemyIntelApi.getEnemy(accountId, selectedId),
        reload(),
      ]);
      if (accountRef.current === accountId) setDetail(d);
    } catch (err) {
      setFlash({ kind: 'err', text: err instanceof Error ? err.message : 'Force scrape failed' });
    } finally {
      setScrapingOne(false);
    }
  };

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Enemy intel"
        subtitle="Profili igrača — scrape + sela + savez."
      />
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link>{' '}
            to see enemy intel.
          </p>
        </Card>
      )}

      {accountId != null && (
        <>
          <Card title="Watchlist">
            <div className="toolbar">
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={onRegister}
                disabled={busy !== null}
                title="Scan IncomingAttack rows from the last 48h and add their attackers to the watchlist."
              >
                {busy === 'register' ? 'Registering…' : 'Register enemies from recent attacks'}
              </button>
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={onRefreshStale}
                disabled={busy !== null}
                title="Re-scrape up to 10 profiles whose lastScrapedAt is older than 6 hours."
              >
                {busy === 'refresh' ? 'Refreshing…' : 'Refresh stale (max 10)'}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void reload()}
                disabled={loading || busy !== null}
              >
                {loading ? 'Loading…' : 'Refresh list'}
              </button>
              <span className="muted" style={{ marginLeft: 'auto' }}>
                {enemies.length} enem{enemies.length === 1 ? 'y' : 'ies'} tracked
              </span>
            </div>

            {error && <p className="form__msg form__msg--error">{error}</p>}
            {flash && (
              <p
                className={
                  flash.kind === 'ok'
                    ? 'form__msg form__msg--ok'
                    : 'form__msg form__msg--error'
                }
                style={{ marginTop: '0.5rem' }}
              >
                {flash.text}
              </p>
            )}

            {enemies.length === 0 && !loading && !error && (
              <p className="muted" style={{ marginTop: '0.5rem' }}>
                Watchlist is empty. Click "Register enemies from recent attacks" once you have
                an incoming attack with a known attacker name — a daily map.sql sync is also
                required so we can resolve the name to a player uid.
              </p>
            )}

            {enemies.length > 0 && (
              <table className="table" style={{ tableLayout: 'fixed' }}>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th style={{ width: '10rem' }}>Alliance</th>
                    <th style={{ width: '7rem' }}>Tribe</th>
                    <th style={{ width: '5rem' }}>Pop</th>
                    <th style={{ width: '5rem' }}>Villages</th>
                    <th style={{ width: '6rem' }}>Hero lvl</th>
                    <th style={{ width: '8rem' }}>Last scrape</th>
                    <th style={{ width: '9rem' }}>Last change</th>
                  </tr>
                </thead>
                <tbody>
                  {enemies.map((e) => (
                    <EnemyRow
                      key={e.id}
                      enemy={e}
                      selected={e.id === selectedId}
                      onClick={() => setSelectedId(e.id)}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          {selectedId != null && (
            <Card title="Detail">
              <div className="toolbar">
                <button
                  type="button"
                  className="btn btn--primary btn--sm"
                  onClick={onForceScrape}
                  disabled={scrapingOne || busy !== null}
                  title="Scrape this one enemy's profile now."
                >
                  {scrapingOne ? 'Scraping…' : 'Force scrape now'}
                </button>
                <button
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => setSelectedId(null)}
                >
                  Close
                </button>
              </div>

              {detailLoading && <p className="muted">Loading detail…</p>}
              {detailError && <p className="form__msg form__msg--error">{detailError}</p>}

              {detail && (
                <DetailPanel detail={detail} />
              )}
            </Card>
          )}
        </>
      )}
    </div>
  );
}

function EnemyRow({
  enemy,
  selected,
  onClick,
}: {
  enemy: EnemyPlayer;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <tr
      onClick={onClick}
      style={{
        cursor: 'pointer',
        background: selected ? 'rgba(64, 128, 255, 0.08)' : undefined,
      }}
    >
      <td>{enemy.playerName}</td>
      <td>
        {enemy.allianceName ? (
          <span className="chip">{enemy.allianceName}</span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>{tribeName(enemy.tribeId)}</td>
      <td>{enemy.currentPopulation ?? <span className="muted">—</span>}</td>
      <td>{enemy.villageCount ?? <span className="muted">—</span>}</td>
      <td>{enemy.heroLevel ?? <span className="muted">—</span>}</td>
      <td>
        <StatusBadge kind={staleKind(enemy.daysSinceLastScrape)}>
          {enemy.daysSinceLastScrape == null ? '—' : `${enemy.daysSinceLastScrape}d`}
        </StatusBadge>
      </td>
      <td>
        {enemy.lastChangeAt ? (
          <span className="muted" title={new Date(enemy.lastChangeAt).toLocaleString()}>
            {daysSince(enemy.lastChangeAt)}d ago
          </span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
    </tr>
  );
}

function DetailPanel({ detail }: { detail: EnemyPlayerDetail }) {
  const { player, snapshots } = detail;

  // Walk the snapshots oldest-first internally so we can compare each entry
  // to its true predecessor for the colour code; the table itself still
  // renders newest-first because that's what the timeline reads naturally.
  const newestFirst = snapshots;
  // Pre-compute, for each row, the predecessor's hero level so we can colour
  // the row even if the diffSummary string is empty (e.g. first snapshot
  // since a parser fix). Falls back to the next-older snapshot in the list.
  const indexByPredecessorHero = newestFirst.map((_s, i) => {
    const olderIdx = i + 1; // list is newest-first, so older sits below
    if (olderIdx >= newestFirst.length) return null;
    return newestFirst[olderIdx].heroLevel;
  });

  return (
    <div>
      <dl className="kv">
        <div>
          <dt>Name</dt>
          <dd>{player.playerName}</dd>
        </div>
        <div>
          <dt>Travian uid</dt>
          <dd><code className="code-chip">{player.travianUid}</code></dd>
        </div>
        <div>
          <dt>Tribe</dt>
          <dd>{tribeName(player.tribeId)}</dd>
        </div>
        <div>
          <dt>Alliance</dt>
          <dd>{player.allianceName ?? <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Population</dt>
          <dd>{player.currentPopulation ?? <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Villages</dt>
          <dd>{player.villageCount ?? <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Hero level / xp</dt>
          <dd>
            {player.heroLevel ?? <span className="muted">—</span>}
            {player.heroXp != null && <span className="muted"> ({player.heroXp} xp)</span>}
          </dd>
        </div>
        <div>
          <dt>Hero production bonus</dt>
          <dd>
            {player.heroProductionBonus != null
              ? `+${player.heroProductionBonus}%`
              : <span className="muted">—</span>}
          </dd>
        </div>
        <div>
          <dt>First seen</dt>
          <dd>{new Date(player.firstSeenAt).toLocaleString()}</dd>
        </div>
        <div>
          <dt>Last scrape</dt>
          <dd>{new Date(player.lastScrapedAt).toLocaleString()}</dd>
        </div>
      </dl>

      <hr className="card__sep" />

      <h3 className="card__title" style={{ marginTop: 0 }}>
        Timeline ({snapshots.length} most recent)
      </h3>

      {snapshots.length === 0 && (
        <p className="muted">
          No snapshots yet. Click "Force scrape now" above to record the first one.
        </p>
      )}

      {snapshots.length > 0 && (
        <table className="table" style={{ tableLayout: 'fixed' }}>
          <thead>
            <tr>
              <th style={{ width: '11rem' }}>When</th>
              <th style={{ width: '4.5rem' }}>Hero</th>
              <th style={{ width: '4.5rem' }}>Vil</th>
              <th style={{ width: '5rem' }}>Pop</th>
              <th style={{ width: '8rem' }}>Alliance</th>
              <th>Change</th>
            </tr>
          </thead>
          <tbody>
            {newestFirst.map((s, i) => {
              const predHero = indexByPredecessorHero[i];
              const heroColour = colourForHeroChange(predHero, s.heroLevel);
              return (
                <tr key={s.id}>
                  <td className="muted">{new Date(s.capturedAt).toLocaleString()}</td>
                  <td style={{ color: heroColour ?? undefined, fontWeight: heroColour ? 600 : undefined }}>
                    {s.heroLevel ?? '—'}
                  </td>
                  <td>{s.villageCount ?? '—'}</td>
                  <td>{s.currentPopulation ?? '—'}</td>
                  <td>{s.allianceName ?? <span className="muted">—</span>}</td>
                  <td>
                    {s.diffSummary && s.diffSummary.length > 0
                      ? <span className="muted">{s.diffSummary}</span>
                      : <span className="muted">—</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

/**
 * Resolve a numeric tribe id to its English display name. Mirrors the
 * mapping the backend uses in {@code MapPlayer.tribeId} comments and in
 * {@code ProfileScraper.tribeFromId}.
 */
function tribeName(tribeId: number | null | undefined): string {
  if (tribeId == null) return '—';
  switch (tribeId) {
    case 1: return 'Romans';
    case 2: return 'Teutons';
    case 3: return 'Gauls';
    case 4: return 'Nature';
    case 5: return 'Natars';
    case 6: return 'Egyptians';
    case 7: return 'Huns';
    case 8: return 'Spartans';
    case 9: return 'Vikings';
    default: return `Tribe ${tribeId}`;
  }
}

/**
 * Hero level change colour:
 *   - drop  → red    (the killer signal: they died)
 *   - rise  → blue   (they leveled up, fought something)
 *   - other → no colour
 *
 * Both sides null collapses to no change; null-to-value or value-to-null is
 * also treated as no signal (the parser missed a row, not the player).
 */
function colourForHeroChange(prev: number | null | undefined, next: number | null | undefined): string | null {
  if (prev == null || next == null) return null;
  if (next < prev) return '#d33'; // drop / death
  if (next > prev) return '#36c'; // level up
  return null;
}

/**
 * StatusBadge kind for the "days since last scrape" chip. >2d means we're
 * actively behind schedule.
 */
function staleKind(days: number | null): 'up' | 'down' | 'loading' | 'idle' {
  if (days == null) return 'idle';
  if (days <= 0) return 'up';
  if (days <= 1) return 'idle';
  if (days <= 2) return 'loading';
  return 'down';
}

function daysSince(iso: string): number {
  const ms = Date.now() - new Date(iso).getTime();
  return Math.max(0, Math.floor(ms / 86_400_000));
}
