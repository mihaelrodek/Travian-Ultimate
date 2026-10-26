import { useCallback, useEffect, useMemo, useState } from 'react';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import ServerPickerCard from '../features/servers/ServerPickerCard';
import { useCurrentServer } from '../hooks/useCurrentServer';
import { intelApi } from '../api/intel';
import { serversApi } from '../api/servers';
import {
  CROPPER_CLASSIFICATION_LABELS,
  TRIBE_LABELS,
  type CropperCandidate,
  type CropperClassification,
} from '../types/intel';
import type { PivotVillage } from '../types/server';

/**
 * Cropper finder page (server-scoped).
 * <p>
 * Picks a pivot village from any account on the chosen Travian server, then
 * asks the backend for likely-cropper candidates within a chosen radius. The
 * bucket logic is a heuristic over {@code map.sql} fields (capital +
 * population + village count) — see {@code CropperClassifierService} for the
 * exact rules. Unoccupied 15c / 9c tiles do <strong>not</strong> appear in
 * {@code map.sql} so this page only lists currently-settled croppers; the
 * hint banner at the top flags the limitation for the user.
 */

const DEFAULT_MAX_DISTANCE = 30;
const ALL_BUCKETS: CropperClassification[] = ['HIGH_15C', 'LIKELY_15C', 'POSSIBLE_15C', 'LIKELY_9C'];

const TRIBE_OF = (tribeId: number | null): string => {
  if (tribeId == null) return '—';
  return TRIBE_LABELS[tribeId] ?? String(tribeId);
};

const BUCKET_PILL_CLASS: Record<CropperClassification, string> = {
  HIGH_15C: 'pill pill--success',
  LIKELY_15C: 'pill pill--success',
  POSSIBLE_15C: 'pill',
  LIKELY_9C: 'pill',
  UNKNOWN: 'pill',
};

export default function CroppersPage() {
  const { serverId, setCurrentServer } = useCurrentServer();

  // --- Filter inputs ---
  const [nearVillageId, setNearVillageId] = useState<number | ''>('');
  const [maxDistance, setMaxDistance] = useState<number | ''>(DEFAULT_MAX_DISTANCE);
  const [selectedBuckets, setSelectedBuckets] = useState<CropperClassification[]>(ALL_BUCKETS);
  // Default off: most users want unoccupied croppers (i.e. side-step active accounts).
  const [includeOccupied, setIncludeOccupied] = useState<boolean>(false);

  // Results / fetch state
  const [results, setResults] = useState<CropperCandidate[] | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Pivot-village picker source — villages on every account on this server.
  const [serverVillages, setServerVillages] = useState<PivotVillage[]>([]);

  // Reclassify button state
  const [reclassifyBusy, setReclassifyBusy] = useState<boolean>(false);
  const [reclassifyMsg, setReclassifyMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  // Reset pivot/results whenever the server changes so we never query against
  // a village id that belongs to a different world.
  useEffect(() => {
    setNearVillageId('');
    setResults(null);
    setError(null);
    setReclassifyMsg(null);
  }, [serverId]);

  // Load the server's villages once for the pivot dropdown.
  useEffect(() => {
    if (serverId == null) {
      setServerVillages([]);
      return;
    }
    let cancelled = false;
    serversApi
      .listVillages(serverId)
      .then((vs) => {
        if (!cancelled) {
          setServerVillages(vs);
          // Auto-pick the first village so the user doesn't have to.
          if (vs.length > 0) setNearVillageId((cur) => (cur === '' ? vs[0].id : cur));
        }
      })
      .catch(() => {
        if (!cancelled) setServerVillages([]);
      });
    return () => {
      cancelled = true;
    };
  }, [serverId]);

  const search = useCallback(async () => {
    if (serverId == null) {
      setResults(null);
      return;
    }
    if (nearVillageId === '') {
      setError('Pick a pivot village first.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      // If the user unchecked every bucket, short-circuit to an empty result
      // rather than asking the backend (which would helpfully default to all
      // buckets and contradict the visible UI state).
      if (selectedBuckets.length === 0) {
        setResults([]);
        return;
      }
      const data = await intelApi.findCroppers(serverId, {
        nearVillageId: Number(nearVillageId),
        maxDistance: maxDistance === '' ? undefined : Number(maxDistance),
        classifications: selectedBuckets,
        includeOccupied,
      });
      setResults(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load croppers');
      setResults(null);
    } finally {
      setLoading(false);
    }
  }, [serverId, nearVillageId, maxDistance, selectedBuckets, includeOccupied]);

  const reclassify = async () => {
    if (serverId == null) return;
    setReclassifyBusy(true);
    setReclassifyMsg(null);
    try {
      const resp = await intelApi.reclassifyCroppers(serverId);
      setReclassifyMsg({
        kind: 'ok',
        text: `Re-classified — ${resp.classified} candidate(s) flagged.`,
      });
      // If we already have results showing, refresh them so the bucket badges
      // reflect the new classification.
      if (results) await search();
    } catch (err) {
      setReclassifyMsg({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Reclassify failed',
      });
    } finally {
      setReclassifyBusy(false);
    }
  };

  const toggleBucket = (b: CropperClassification) => {
    setSelectedBuckets((cur) =>
      cur.includes(b) ? cur.filter((x) => x !== b) : [...cur, b],
    );
  };

  const pivotLabel = useMemo(() => {
    if (nearVillageId === '') return null;
    const v = serverVillages.find((x) => x.id === Number(nearVillageId));
    return v ? `${v.accountName} → ${v.villageName} (${v.x}|${v.y})` : null;
  }, [nearVillageId, serverVillages]);

  const total = results?.length ?? 0;

  return (
    <div className="stack">
      <div>
        <GlassHeader
        title="Cropperi"
        subtitle="9c/15c kandidati u radijusu mog sela."
      />
      </div>

      <Card muted>
        <p>
          <strong>Heads-up:</strong> Croppers are estimated from village
          population + the capital flag in the daily {' '}
          <code>map.sql</code>. Unoccupied croppers can't be discovered from
          map.sql alone — only currently-settled ones are listed here. Empty
          tile discovery (a per-tile scrape) is on the roadmap.
        </p>
      </Card>

      <ServerPickerCard serverId={serverId} onChange={setCurrentServer} />

      {serverId == null && (
        <Card>
          <p className="muted">
            Pick a Travian server above to start searching.
          </p>
        </Card>
      )}

      {serverId != null && (
        <>
          <Card title="Filters" hint="Hit Search after editing — filters don't auto-submit.">
            <div
              className="form-grid"
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(12rem, 1fr))',
                gap: '0.75rem',
              }}
            >
              <label className="form__field">
                <span>Pivot village</span>
                <select
                  value={nearVillageId === '' ? '' : String(nearVillageId)}
                  onChange={(e) =>
                    setNearVillageId(e.target.value === '' ? '' : Number(e.target.value))
                  }
                  disabled={serverVillages.length === 0}
                >
                  <option value="">
                    {serverVillages.length === 0 ? '— no villages on this server —' : '— pick one —'}
                  </option>
                  {serverVillages.map((v) => (
                    <option key={v.id} value={v.id}>
                      {v.accountName} → {v.villageName} ({v.x}|{v.y})
                    </option>
                  ))}
                </select>
              </label>

              <label className="form__field">
                <span>Max distance (fields)</span>
                <input
                  type="number"
                  min={1}
                  max={400}
                  value={maxDistance}
                  onChange={(e) =>
                    setMaxDistance(e.target.value === '' ? '' : Number(e.target.value))
                  }
                />
              </label>

              <label className="form__field" style={{ alignSelf: 'end' }}>
                <span>&nbsp;</span>
                <label className="form__check">
                  <input
                    type="checkbox"
                    checked={includeOccupied}
                    onChange={(e) => setIncludeOccupied(e.target.checked)}
                  />
                  Include occupied (active players)
                </label>
              </label>
            </div>

            <div style={{ marginTop: '0.5rem' }}>
              <span style={{ display: 'block', fontSize: '0.85rem', marginBottom: '0.25rem' }}>
                Classifications (uncheck to hide a bucket)
              </span>
              <div className="toolbar" style={{ flexWrap: 'wrap', gap: '0.5rem' }}>
                {ALL_BUCKETS.map((b) => (
                  <label key={b} className="form__check">
                    <input
                      type="checkbox"
                      checked={selectedBuckets.includes(b)}
                      onChange={() => toggleBucket(b)}
                    />
                    {CROPPER_CLASSIFICATION_LABELS[b]}
                  </label>
                ))}
              </div>
            </div>

            <div
              className="toolbar"
              style={{ gap: '0.75rem', marginTop: '0.75rem', flexWrap: 'wrap' }}
            >
              <button
                type="button"
                className="btn btn--primary"
                onClick={() => void search()}
                disabled={loading || nearVillageId === ''}
              >
                {loading ? 'Searching…' : 'Search'}
              </button>
              <button
                type="button"
                className="btn btn--ghost"
                onClick={() => void reclassify()}
                disabled={reclassifyBusy}
                title="Re-run the classifier across every tracked village on this server"
              >
                {reclassifyBusy ? 'Re-classifying…' : 'Reclassify now'}
              </button>
              {reclassifyMsg && (
                <span
                  className={
                    reclassifyMsg.kind === 'ok'
                      ? 'form__msg form__msg--success'
                      : 'form__msg form__msg--error'
                  }
                  style={{ flex: 1 }}
                >
                  {reclassifyMsg.text}
                </span>
              )}
            </div>
          </Card>

          <Card
            title={`Results (${total})`}
            hint={
              pivotLabel
                ? `Distances measured from ${pivotLabel}. Results capped at 200.`
                : 'Pick a pivot village above to see distances.'
            }
          >
            {error && <p className="form__msg form__msg--error">{error}</p>}
            {!error && loading && <p className="muted">Loading…</p>}
            {!error && !loading && results === null && (
              <p className="muted">Hit <em>Search</em> to find croppers near your pivot.</p>
            )}
            {!error && !loading && results && results.length === 0 && (
              <p className="muted">
                No matching croppers in that radius. Widen the distance, enable more
                classifications, or hit <em>Reclassify now</em> if the daily sync hasn't
                run since the heuristic was last tweaked.
              </p>
            )}
            {!error && results && results.length > 0 && (
              <table className="table">
                <thead>
                  <tr>
                    <th>Bucket</th>
                    <th>Coords</th>
                    <th>Player</th>
                    <th>Alliance</th>
                    <th>Tribe</th>
                    <th>Pop</th>
                    <th>Villages</th>
                    <th>Distance</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((row) => (
                    <tr key={row.mapVillageId}>
                      <td>
                        <span className={BUCKET_PILL_CLASS[row.cropperClassification]}>
                          {CROPPER_CLASSIFICATION_LABELS[row.cropperClassification]}
                        </span>
                        {row.isCapital && (
                          <span
                            className="pill"
                            title="Player's capital"
                            style={{ marginLeft: '0.25rem' }}
                          >
                            cap
                          </span>
                        )}
                      </td>
                      <td>
                        ({row.x}|{row.y})
                      </td>
                      <td>{row.playerName ?? <span className="muted">—</span>}</td>
                      <td>{row.allianceName ?? <span className="muted">—</span>}</td>
                      <td>{TRIBE_OF(row.tribeId)}</td>
                      <td>{row.population}</td>
                      <td>{row.playerVillageCount ?? <span className="muted">—</span>}</td>
                      <td>{row.distanceFields.toFixed(1)}</td>
                      <td>
                        {row.playerLooksActive ? (
                          <span className="pill">Active</span>
                        ) : (
                          <span className="pill pill--success">Idle</span>
                        )}
                      </td>
                    </tr>
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
