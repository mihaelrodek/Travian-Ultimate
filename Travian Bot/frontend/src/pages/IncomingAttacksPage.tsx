import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import StatusBadge from '../components/StatusBadge';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { incomingApi } from '../api/incoming';
import type {
  AttackAnalysis,
  IncomingAttack,
  ReturnCatchSuggestion,
} from '../types/incoming';
import { ARTIFACT_MULTIPLIERS } from '../types/incoming';

/**
 * "Incoming attacks" screen for the active account.
 *
 * Two cards stacked:
 *  - Incoming attacks — table of waves landing on our villages, with a live
 *    HH:MM:SS countdown that re-renders every second. Acknowledging a row
 *    removes it from the "unread" view (we still keep the row for history).
 *  - Return-catch suggestions — the planner's output. Each row shows when the
 *    attacker is expected home, when our snipe should launch, and from which
 *    village. The bot does NOT auto-send — the static copy makes that
 *    explicit so the user doesn't expect anything to happen by itself.
 *
 * Both sections are scoped to the currently-active account. If no account is
 * picked, we nudge the user to the Accounts page (same pattern as the rest
 * of the per-account pages).
 *
 * The "Scan now" button calls {@code POST .../incoming/scrape}; on success it
 * also re-loads the list + suggestions so the user sees the fresh data
 * immediately. The bot also runs the scrape automatically every 90s server-
 * side, so the manual button is for impatient users / debugging.
 */
export default function IncomingAttacksPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [attacks, setAttacks] = useState<IncomingAttack[]>([]);
  const [suggestions, setSuggestions] = useState<ReturnCatchSuggestion[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [scanning, setScanning] = useState(false);
  const [reanalysing, setReanalysing] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  // Track in-flight account so a stale fetch can't clobber the new view if
  // the user switches accounts mid-load.
  const accountRef = useRef<number | null>(accountId);
  accountRef.current = accountId;

  // Per-second tick to drive the live countdowns. We only render the seconds
  // value once per second; the underlying data refresh stays on its own
  // ~30-second cadence (handled below).
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    const handle = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(handle);
  }, []);

  const reload = useCallback(async () => {
    if (accountId == null) {
      setAttacks([]);
      setSuggestions([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [a, s] = await Promise.all([
        incomingApi.list(accountId),
        incomingApi.listReturnCatch(accountId),
      ]);
      if (accountRef.current !== accountId) return; // stale
      setAttacks(a);
      setSuggestions(s);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load incoming attacks');
    } finally {
      if (accountRef.current === accountId) setLoading(false);
    }
  }, [accountId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Background poll — keep the table fresh while the page is open. 30s
  // matches "fast enough to notice a new wave, slow enough to be polite".
  useEffect(() => {
    if (accountId == null) return;
    const handle = window.setInterval(() => void reload(), 30_000);
    return () => window.clearInterval(handle);
  }, [accountId, reload]);

  const onScan = async () => {
    if (accountId == null) return;
    setScanning(true);
    setFlash(null);
    try {
      const res = await incomingApi.triggerScrape(accountId);
      setFlash({
        kind: 'ok',
        text:
          res.scraped > 0
            ? `Scan finished — ${res.scraped} new wave${res.scraped === 1 ? '' : 's'} recorded.`
            : 'Scan finished — no new attacks.',
      });
      await reload();
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Scan failed',
      });
    } finally {
      setScanning(false);
    }
  };

  const onClearAll = async () => {
    if (accountId == null) return;
    // Native confirm — wiping the table is destructive but cheap to recover
    // from (a fresh paste re-populates), so a single yes/no is enough.
    if (!window.confirm('Delete every recorded incoming attack for this account?')) return;
    setClearing(true);
    setFlash(null);
    try {
      const res = await incomingApi.clearAll(accountId);
      setFlash({
        kind: 'ok',
        text: `Cleared ${res.removed} row${res.removed === 1 ? '' : 's'}.`,
      });
      await reload();
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Clear failed',
      });
    } finally {
      setClearing(false);
    }
  };

  const onReanalyse = async () => {
    if (accountId == null) return;
    setReanalysing(true);
    setFlash(null);
    try {
      const res = await incomingApi.reanalyse(accountId);
      setFlash({
        kind: 'ok',
        text: `Re-analysed ${res.reanalysed} row${res.reanalysed === 1 ? '' : 's'}.`,
      });
      await reload();
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Re-analyse failed',
      });
    } finally {
      setReanalysing(false);
    }
  };

  const onAcknowledge = async (id: number) => {
    if (accountId == null) return;
    try {
      const updated = await incomingApi.acknowledge(accountId, id);
      setAttacks((prev) => prev.map((a) => (a.id === id ? updated : a)));
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Acknowledge failed',
      });
    }
  };

  const sortedAttacks = useMemo(() => {
    // Sort by arrival ascending — soonest hits first.
    return [...attacks].sort((a, b) =>
      a.arrivalAt.localeCompare(b.arrivalAt),
    );
  }, [attacks]);

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Dolazni napadi"
        subtitle="Live odbrojavanje + return-catch planner."
      />
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link>{' '}
            to see incoming attacks.
          </p>
        </Card>
      )}

      {accountId != null && (
        <>
          <Card title="Incoming">
            <div className="toolbar">
              <button
                type="button"
                className="btn btn--primary btn--sm"
                onClick={onScan}
                disabled={scanning}
                title="Trigger an immediate rally-point scrape on the open session."
              >
                {scanning ? 'Scanning…' : 'Scan now'}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void reload()}
                disabled={loading || scanning}
              >
                {loading ? 'Refreshing…' : 'Refresh'}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void onReanalyse()}
                disabled={reanalysing || loading || scanning || sortedAttacks.length === 0}
                title="Re-run the Tournament-Square inverse solver against every stored row. Useful after filling in Server.speedMultiplier or updating the troop catalog."
              >
                {reanalysing ? 'Re-analysing…' : 'Re-analyse all'}
              </button>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={() => void onClearAll()}
                disabled={clearing || loading || scanning || sortedAttacks.length === 0}
                title="Delete every recorded incoming for this account. Use after a parser fix lands so you can re-import cleanly."
              >
                {clearing ? 'Clearing…' : 'Clear all'}
              </button>
              <span className="muted" style={{ marginLeft: 'auto' }}>
                {sortedAttacks.length} wave{sortedAttacks.length === 1 ? '' : 's'}
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

            {sortedAttacks.length === 0 && !loading && !error && (
              <p className="muted" style={{ marginTop: '0.5rem' }}>
                Nothing inbound — quiet on the wire.
              </p>
            )}

            {sortedAttacks.length > 0 && (
              <table className="table" style={{ tableLayout: 'fixed' }}>
                <thead>
                  <tr>
                    <th style={{ width: '6rem' }}>Type</th>
                    <th style={{ width: '8rem' }}>Lands in</th>
                    <th>Attacker</th>
                    <th style={{ width: '8rem' }}>Alliance</th>
                    <th style={{ width: '7rem' }}>Source</th>
                    <th style={{ width: '6rem' }}>Distance</th>
                    <th style={{ width: '9rem' }}>Est. class</th>
                    <th style={{ width: '11rem' }}>Likely unit / TS</th>
                    <th style={{ width: '4rem' }}>Waves</th>
                    <th style={{ width: '7rem' }} />
                  </tr>
                </thead>
                <tbody>
                  {sortedAttacks.map((a) => (
                    <IncomingRow
                      key={a.id}
                      attack={a}
                      now={now}
                      accountId={accountId}
                      onAcknowledge={() => void onAcknowledge(a.id)}
                      onUpdated={(updated) =>
                        setAttacks((prev) =>
                          prev.map((row) => (row.id === updated.id ? updated : row)),
                        )
                      }
                      onError={(msg) => setFlash({ kind: 'err', text: msg })}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          <Card
            title="Return-catch suggestions"
            hint="Hit launch via the village → rally point yourself — the bot will not auto-send."
          >
            {suggestions.length === 0 && (
              <p className="muted" style={{ margin: 0 }}>
                No live snipe windows. Suggestions appear when we know an
                attacker's source coordinates and the launch time hasn't
                already passed.
              </p>
            )}

            {suggestions.length > 0 && (
              <table className="table" style={{ tableLayout: 'fixed' }}>
                <thead>
                  <tr>
                    <th style={{ width: '14rem' }}>From village</th>
                    <th style={{ width: '8rem' }}>Target</th>
                    <th style={{ width: '9rem' }}>Launch in</th>
                    <th>Attacker arrives home</th>
                    <th style={{ width: '6rem' }}>Distance</th>
                    <th>Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {groupSuggestionsByAttack(suggestions).map((group) => (
                    <SuggestionGroupRow
                      key={`group-${group.attackId}`}
                      options={group.options}
                      now={now}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </div>
  );
}

function IncomingRow({
  attack,
  now,
  accountId,
  onAcknowledge,
  onUpdated,
  onError,
}: {
  attack: IncomingAttack;
  now: number;
  accountId: number;
  onAcknowledge: () => void;
  onUpdated: (updated: IncomingAttack) => void;
  onError: (msg: string) => void;
}) {
  const seconds = Math.max(0, Math.floor((new Date(attack.arrivalAt).getTime() - now) / 1000));
  const kind = arrivalKind(seconds);
  const [showBreakdown, setShowBreakdown] = useState(false);
  const [savingArtifact, setSavingArtifact] = useState(false);
  const analysis = useMemo<AttackAnalysis | null>(() => {
    if (!attack.analysisJson) return null;
    try {
      return JSON.parse(attack.analysisJson) as AttackAnalysis;
    } catch {
      return null;
    }
  }, [attack.analysisJson]);

  const onArtifactChange = async (raw: string) => {
    // Empty string from the placeholder option → clear override.
    const value = raw === '' ? null : Number(raw);
    setSavingArtifact(true);
    try {
      const updated = await incomingApi.setArtifact(accountId, attack.id, value);
      onUpdated(updated);
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Failed to set artifact');
    } finally {
      setSavingArtifact(false);
    }
  };
  return (
    <tr
      style={{
        opacity: attack.acknowledged ? 0.55 : 1,
      }}
    >
      <td>
        <span
          className={`pill pill--${attack.isAttack ? 'danger' : 'warn'}`}
          title={attack.isAttack ? 'Full attack (red)' : 'Raid (yellow)'}
        >
          {attack.isAttack ? 'Attack' : 'Raid'}
        </span>
      </td>
      <td>
        <StatusBadge kind={kind} title={new Date(attack.arrivalAt).toLocaleString()}>
          {formatCountdown(seconds)}
        </StatusBadge>
      </td>
      <td>{attack.attackerName ?? <span className="muted">—</span>}</td>
      <td>
        {attack.attackerAlliance ? (
          <span className="chip">{attack.attackerAlliance}</span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>
        {attack.sourceX != null && attack.sourceY != null ? (
          <code className="code-chip">
            ({attack.sourceX}|{attack.sourceY})
          </code>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>
        {attack.distanceFields != null ? (
          <span title={`${attack.distanceFields.toFixed(2)} fields`}>
            {attack.distanceFields.toFixed(1)}
          </span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>
        {attack.estimatedTroopClass ? (
          <span className="chip" title={
            attack.estimatedSpeedFieldsPerHour != null
              ? `${attack.estimatedSpeedFieldsPerHour.toFixed(1)} fields/hour`
              : 'No speed estimate'
          }>
            {attack.estimatedTroopClass}
          </span>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td>
        {attack.mostLikelyUnit ? (
          <button
            type="button"
            className="link"
            onClick={() => setShowBreakdown((v) => !v)}
            title={
              analysis?.summary ??
              `Inverse-solver pick — click for ${analysis?.candidates?.length ?? 0} candidate(s)`
            }
            style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', textAlign: 'left' }}
          >
            <span style={{ fontWeight: 500 }}>
              {attack.mostLikelyTsLevel != null && `TS ${attack.mostLikelyTsLevel} · `}
              {attack.mostLikelyUnit}
            </span>
            {attack.observedFieldsPerHour != null && (
              <span className="muted" style={{ fontSize: '0.8em' }}>
                {attack.observedFieldsPerHour.toFixed(1)} f/h
              </span>
            )}
          </button>
        ) : attack.observedFieldsPerHour != null ? (
          <span className="muted" title="No solver match within tolerance">
            {attack.observedFieldsPerHour.toFixed(1)} f/h
          </span>
        ) : (
          <span className="muted">—</span>
        )}
        <div
          style={{
            marginTop: '0.25rem',
            display: 'flex',
            alignItems: 'center',
            gap: '0.25rem',
            fontSize: '0.75em',
          }}
        >
          <span className="muted">art:</span>
          <select
            value={attack.manualArtifactMultiplier ?? ''}
            onChange={(e) => void onArtifactChange(e.target.value)}
            disabled={savingArtifact}
            title="Speed artifact (in-game picker). Empty = no artifact assumed; the solver re-runs on change."
            style={{ fontSize: 'inherit', padding: '0.05rem 0.2rem' }}
          >
            <option value="">—</option>
            {ARTIFACT_MULTIPLIERS.filter((m) => m.value !== 1.0).map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
          {savingArtifact && <span className="muted">…</span>}
        </div>
        {showBreakdown && analysis && (
          <AnalysisBreakdown analysis={analysis} />
        )}
      </td>
      <td>{attack.waveCount}</td>
      <td>
        {attack.acknowledged ? (
          <span className="muted">ack'd</span>
        ) : (
          <button type="button" className="btn btn--ghost btn--sm" onClick={onAcknowledge}>
            Ack
          </button>
        )}
      </td>
    </tr>
  );
}

/**
 * One incoming attack collapsed to a single row with a dropdown of every
 * launchable from-village. Default selection is the suggestion with the
 * SOONEST launchAt (the planner pre-sorts that way, but we don't rely on
 * input order here — the grouping helper picks it explicitly). Switching the
 * dropdown updates the displayed travel time / distance / countdown
 * instantly; no backend round-trip needed because the planner already
 * returned a row per (attack × village).
 */
function SuggestionGroupRow({
  options,
  now,
}: {
  options: ReturnCatchSuggestion[];
  now: number;
}) {
  // options is non-empty by construction (groupSuggestionsByAttack skips
  // empties); take the first as the default — it's already the soonest.
  const [selectedVillageId, setSelectedVillageId] = useState<number>(
    options[0].fromVillageId,
  );
  const selected =
    options.find((o) => o.fromVillageId === selectedVillageId) ?? options[0];
  const launchSeconds = Math.max(
    0,
    Math.floor((new Date(selected.launchAt).getTime() - now) / 1000),
  );
  return (
    <tr>
      <td>
        {options.length === 1 ? (
          <Link to={`/villages/${selected.fromVillageId}`} className="link">
            {selected.fromVillageName}
          </Link>
        ) : (
          <select
            value={selectedVillageId}
            onChange={(e) => setSelectedVillageId(Number(e.target.value))}
            style={{ width: '100%' }}
            title="Pick which of your villages to launch the snipe from."
          >
            {options.map((o) => (
              <option key={o.fromVillageId} value={o.fromVillageId}>
                {o.fromVillageName} — {o.distance.toFixed(0)}f
              </option>
            ))}
          </select>
        )}
      </td>
      <td>
        <code className="code-chip">
          ({selected.toX}|{selected.toY})
        </code>
      </td>
      <td>
        <StatusBadge kind={launchSeconds < 60 ? 'down' : 'up'}>
          {formatCountdown(launchSeconds)}
        </StatusBadge>
      </td>
      <td className="muted">
        {new Date(selected.attackerArrivesHomeAt).toLocaleString()}
      </td>
      <td>{selected.distance.toFixed(1)}</td>
      <td>{selected.reason}</td>
    </tr>
  );
}

interface SuggestionGroup {
  attackId: number;
  options: ReturnCatchSuggestion[];
}

/**
 * Collapse the planner's flat (attack × village) list into one entry per
 * attack. Each entry carries every viable village option, sorted by launchAt
 * ascending so the dropdown's default selection is the soonest snipe window
 * — the most actionable pick.
 */
function groupSuggestionsByAttack(
  suggestions: ReturnCatchSuggestion[],
): SuggestionGroup[] {
  const byAttack = new Map<number, ReturnCatchSuggestion[]>();
  for (const s of suggestions) {
    const list = byAttack.get(s.attackId) ?? [];
    list.push(s);
    byAttack.set(s.attackId, list);
  }
  // Sort options within each group, then sort groups by earliest launch
  // across their options — same ordering principle as the old flat list.
  const groups: SuggestionGroup[] = Array.from(byAttack.entries()).map(
    ([attackId, options]) => {
      options.sort((a, b) => a.launchAt.localeCompare(b.launchAt));
      return { attackId, options };
    },
  );
  groups.sort((a, b) =>
    a.options[0].launchAt.localeCompare(b.options[0].launchAt),
  );
  return groups;
}

/**
 * Inline panel rendered below the "Likely unit / TS" cell when the user
 * clicks it. Lists every candidate from the inverse solver with its
 * deviation, TS level, and modifier breakdown so the user can sanity-check
 * the top pick against alternatives.
 */
function AnalysisBreakdown({ analysis }: { analysis: AttackAnalysis }) {
  if (!analysis.candidates || analysis.candidates.length === 0) {
    return (
      <div className="muted" style={{ marginTop: '0.25rem', fontSize: '0.85em' }}>
        {analysis.summary || 'No candidates'}
      </div>
    );
  }
  return (
    <div
      style={{
        marginTop: '0.35rem',
        padding: '0.4rem 0.5rem',
        background: 'var(--surface-alt, rgba(255,255,255,0.04))',
        borderRadius: '4px',
        fontSize: '0.8em',
      }}
    >
      <div className="muted" style={{ marginBottom: '0.25rem' }}>
        {analysis.summary}
      </div>
      {analysis.candidates.map((c, i) => (
        <div key={`${c.tribeId}-${c.tIndex}-${c.tsLevel}-${i}`} style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap' }}>
          <span style={{ fontWeight: i === 0 ? 500 : 400 }}>
            {i + 1}. {c.unitName}
          </span>
          <span className="muted">
            TS {c.tsLevel}
            {c.bootsBonus > 0 ? ` · +${Math.round(c.bootsBonus * 100)}% boots` : ''}
            {Math.abs(c.artifactMultiplier - 1.0) > 0.01
              ? ` · ${formatArtifactLabel(c.artifactMultiplier)} artifact`
              : ''}
            {` · Δ ${(c.deviation * 100).toFixed(1)}%`}
          </span>
        </div>
      ))}
    </div>
  );
}

/**
 * Render an artifact multiplier the same way the in-game picker does:
 * whole-number multipliers without trailing decimals, partials with two
 * significant figures.
 */
function formatArtifactLabel(m: number): string {
  const match = ARTIFACT_MULTIPLIERS.find((opt) => Math.abs(opt.value - m) < 0.01);
  return match ? match.label : `${m.toFixed(2)}x`;
}

/**
 * Formats a non-negative seconds duration as HH:MM:SS. Used by both
 * countdowns (arrival and launch); pulled out so a future i18n switch is a
 * one-place change.
 */
function formatCountdown(totalSeconds: number): string {
  if (totalSeconds <= 0) return '00:00:00';
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  const pad = (n: number) => (n < 10 ? `0${n}` : `${n}`);
  return `${pad(h)}:${pad(m)}:${pad(s)}`;
}

/**
 * Maps the arrival countdown to a {@link StatusBadge} kind so the badge
 * colour carries urgency: red under a minute, loading-amber under 10
 * minutes, idle otherwise.
 */
function arrivalKind(seconds: number): 'up' | 'down' | 'loading' | 'idle' {
  if (seconds <= 60) return 'down';
  if (seconds <= 600) return 'loading';
  return 'idle';
}
