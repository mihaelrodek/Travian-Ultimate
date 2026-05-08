import { useEffect, useMemo, useState } from 'react';
import Card from '../../components/Card';
import { troopsApi } from '../../api/troops';
import { accountSettingsApi, villageSettingsApi } from '../../api/settings';
import { villagesApi } from '../../api/villages';
import { ApiException } from '../../types/api';
import type { TrainConfigItem, TroopBuilding, TroopCatalogEntry } from '../../types/troops';
import { TROOP_BUILDINGS } from '../../types/troops';
import type { VillageSettings, VillageSettingsUpdate } from '../../types/villageSettings';
import type { Village } from '../../types/village';

/**
 * Per-village troop training tab — the only place the user configures what
 * the bot trains and how often.
 * <p>
 * Layout, top to bottom:
 *   1. <strong>Cycle settings</strong>: a master "enable training" toggle
 *      and the random-delay window (min/max minutes between cycles). These
 *      live on {@link VillageSettings}.
 *   2. Three sub-cards — Barracks / Stable / Workshop — each with one row
 *      per troop the player's tribe can train there. Each row carries
 *      min/max quantity inputs; rows where both are zero are dropped on
 *      save (the backend's "this troop isn't part of my plan" signal).
 *      Sub-cards whose building isn't built yet say so explicitly so the
 *      user knows where the gap is.
 * <p>
 * Both halves share a single Save button and a single dirty flag. We deep-
 * compare the draft to the loaded snapshot to know whether to enable Save —
 * mirrors the templates page so users have one consistent "you have unsaved
 * changes" pattern across the app.
 */
interface Props {
  villageId: number;
}

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'noTribe' }
  | { kind: 'error'; message: string };

type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

interface CycleDraft {
  enabled: boolean;
  minMinutes: number;
  maxMinutes: number;
}

export default function VillageTroopsTab({ villageId }: Props) {
  const [loadState, setLoadState] = useState<LoadState>({ kind: 'loading' });
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });

  const [village, setVillage] = useState<Village | null>(null);
  const [catalog, setCatalog] = useState<TroopCatalogEntry[]>([]);
  // Full village settings — needed so saving training settings doesn't blow
  // away unrelated NPC / scrape / crop-watcher fields. We round-trip the
  // whole record on PUT.
  const [settings, setSettings] = useState<VillageSettings | null>(null);

  // Editable copies of the two halves of the form, plus their snapshots so
  // we can detect unsaved changes the same way the templates page does.
  const [cycle, setCycle] = useState<CycleDraft>({ enabled: false, minMinutes: 15, maxMinutes: 60 });
  const [cycleSnapshot, setCycleSnapshot] = useState<CycleDraft>({ enabled: false, minMinutes: 15, maxMinutes: 60 });
  const [rows, setRows] = useState<Record<string, { minQty: number; maxQty: number }>>({});
  const [rowsSnapshot, setRowsSnapshot] = useState<Record<string, { minQty: number; maxQty: number }>>({});

  useEffect(() => {
    let cancelled = false;
    setLoadState({ kind: 'loading' });
    setSaveState({ kind: 'idle' });

    villagesApi.get(villageId)
      .then(async (v) => {
        if (cancelled) return;
        setVillage(v);

        const [accountSettings, vSettings] = await Promise.all([
          accountSettingsApi.get(v.accountId),
          villageSettingsApi.get(villageId),
        ]);
        if (cancelled) return;

        setSettings(vSettings);
        const c: CycleDraft = {
          enabled: vSettings.trainTroopsEnabled,
          minMinutes: vSettings.trainTroopsMinMinutes,
          maxMinutes: vSettings.trainTroopsMaxMinutes,
        };
        setCycle(c);
        setCycleSnapshot(c);

        if (!accountSettings.tribe) {
          setLoadState({ kind: 'noTribe' });
          return;
        }

        const [catalogue, trainConfig] = await Promise.all([
          troopsApi.catalog(accountSettings.tribe),
          troopsApi.getTrainConfig(villageId),
        ]);
        if (cancelled) return;
        setCatalog(catalogue);
        const map: Record<string, { minQty: number; maxQty: number }> = {};
        for (const it of trainConfig.items) {
          map[it.troopCode] = { minQty: it.minQty, maxQty: it.maxQty };
        }
        setRows(map);
        setRowsSnapshot(map);
        setLoadState({ kind: 'ready' });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : 'Failed to load training data';
        setLoadState({ kind: 'error', message });
      });

    return () => { cancelled = true; };
  }, [villageId]);

  // Which training buildings does this village have? Match by normalized
  // name — scraper writes names like "Barracks", not our enum strings.
  const availableBuildings = useMemo<Set<TroopBuilding>>(() => {
    const out = new Set<TroopBuilding>();
    if (!village) return out;
    for (const b of village.buildings) {
      if (!b.name) continue;
      const normalized = b.name.toUpperCase().replace(/\s+/g, '_');
      if (normalized === 'BARRACKS' || normalized === 'GREAT_BARRACKS') out.add('BARRACKS');
      else if (normalized === 'STABLE' || normalized === 'GREAT_STABLE') out.add('STABLE');
      else if (normalized === 'WORKSHOP') out.add('WORKSHOP');
    }
    return out;
  }, [village]);

  const isDirty = useMemo(() => {
    if (loadState.kind !== 'ready' && loadState.kind !== 'noTribe') return false;
    return JSON.stringify(cycle) !== JSON.stringify(cycleSnapshot)
        || JSON.stringify(rows) !== JSON.stringify(rowsSnapshot);
  }, [cycle, cycleSnapshot, rows, rowsSnapshot, loadState.kind]);

  // Group catalogue per building so the three sub-cards can render in
  // isolation without filtering on every render.
  // IMPORTANT: this useMemo MUST stay above the early `if (loadState ...)`
  // returns below — React's rules of hooks require the same hook count on
  // every render, and an early return between `useMemo` calls would skip
  // this one on the first ('loading') render and crash with a hook-count
  // mismatch on the next ('ready') render.
  const catalogByBuilding = useMemo(() => {
    const out: Record<TroopBuilding, TroopCatalogEntry[]> = {
      BARRACKS: [],
      STABLE: [],
      WORKSHOP: [],
    };
    for (const e of catalog) out[e.building].push(e);
    for (const k of TROOP_BUILDINGS) out[k].sort((a, b) => a.troopSlot - b.troopSlot);
    return out;
  }, [catalog]);

  if (loadState.kind === 'loading') return <p className="muted">Loading training…</p>;
  if (loadState.kind === 'error') return <p className="form__msg form__msg--error">{loadState.message}</p>;

  const setRow = (code: string, patch: Partial<{ minQty: number; maxQty: number }>) => {
    setRows((prev) => {
      const cur = prev[code] ?? { minQty: 0, maxQty: 0 };
      return { ...prev, [code]: { ...cur, ...patch } };
    });
  };

  const save = async () => {
    if (!settings) return;
    setSaveState({ kind: 'saving' });
    try {
      // Settings PUT: round-trip the whole record but overwrite the train-
      // related fields. Min/max are auto-swapped server-side if reversed.
      const settingsBody: VillageSettingsUpdate = {
        npcEnabled: settings.npcEnabled,
        npcTriggerPct: settings.npcTriggerPct,
        npcRatioWood: settings.npcRatioWood,
        npcRatioClay: settings.npcRatioClay,
        npcRatioIron: settings.npcRatioIron,
        npcRatioCrop: settings.npcRatioCrop,
        goldReserve: settings.goldReserve,
        autoScrapeEnabled: settings.autoScrapeEnabled,
        autoScrapeIntervalMinutes: settings.autoScrapeIntervalMinutes,
        cropWatcherEnabled: settings.cropWatcherEnabled,
        cropWatcherThresholdPct: settings.cropWatcherThresholdPct,
        cropWatcherMinGold: settings.cropWatcherMinGold,
        trainTroopsEnabled: cycle.enabled,
        trainTroopsMinMinutes: cycle.minMinutes,
        trainTroopsMaxMinutes: cycle.maxMinutes,
      };
      const savedSettings = await villageSettingsApi.update(villageId, settingsBody);
      setSettings(savedSettings);

      // Drop rows where both qty are zero before sending. The backend does the
      // same, but trimming client-side keeps the wire body small and means
      // the next reload doesn't surface cleared-but-still-saved entries.
      const items: TrainConfigItem[] = Object.entries(rows)
        .filter(([, v]) => v.minQty > 0 || v.maxQty > 0)
        .map(([code, v]) => ({ troopCode: code, minQty: v.minQty, maxQty: v.maxQty }));
      const savedConfig = await troopsApi.putTrainConfig(villageId, { items });
      const map: Record<string, { minQty: number; maxQty: number }> = {};
      for (const it of savedConfig.items) {
        map[it.troopCode] = { minQty: it.minQty, maxQty: it.maxQty };
      }
      // Preserve any non-persisted "in flight" rows from the user's draft —
      // they may have typed a min only with max still 0. Snapshot what we
      // just persisted; the user keeps editing from there.
      setRows({ ...rows, ...map });
      setRowsSnapshot(map);
      setCycleSnapshot({
        enabled: savedSettings.trainTroopsEnabled,
        minMinutes: savedSettings.trainTroopsMinMinutes,
        maxMinutes: savedSettings.trainTroopsMaxMinutes,
      });
      setCycle({
        enabled: savedSettings.trainTroopsEnabled,
        minMinutes: savedSettings.trainTroopsMinMinutes,
        maxMinutes: savedSettings.trainTroopsMaxMinutes,
      });
      setSaveState({ kind: 'saved' });
    } catch (err) {
      const message = err instanceof ApiException
        ? err.apiError?.details?.join('; ') || err.apiError?.message || err.message
        : err instanceof Error
          ? err.message
          : 'Save failed';
      setSaveState({ kind: 'error', message });
    }
  };

  return (
    <div className="stack stack--xwide">
      <Card title="Cycle settings">
        <div className="form form--compact">
          <label className="form__check form__field--full">
            <input
              type="checkbox"
              checked={cycle.enabled}
              onChange={(e) => setCycle((c) => ({ ...c, enabled: e.target.checked }))}
            />
            <span>Enable train troops for this village</span>
          </label>

          <div className="form__field">
            <label>Next train executes in (min)</label>
            <input
              type="number"
              min={1}
              max={1440}
              value={cycle.minMinutes}
              onChange={(e) =>
                setCycle((c) => ({
                  ...c,
                  minMinutes: clampInt(e.target.value, 1, 1440),
                }))
              }
              disabled={!cycle.enabled}
            />
          </div>
          <div className="form__field">
            <label>Next train executes in (max)</label>
            <input
              type="number"
              min={1}
              max={1440}
              value={cycle.maxMinutes}
              onChange={(e) =>
                setCycle((c) => ({
                  ...c,
                  maxMinutes: clampInt(e.target.value, 1, 1440),
                }))
              }
              disabled={!cycle.enabled}
            />
          </div>
        </div>
      </Card>

      {loadState.kind === 'noTribe' ? (
        <Card>
          <p className="muted">
            Tribe hasn't been detected for this account yet. The bot auto-detects it on first
            successful login from the in-game /profile page — log the account in and come
            back here.
          </p>
        </Card>
      ) : (
        TROOP_BUILDINGS.map((building) => {
          const entries = catalogByBuilding[building];
          const built = availableBuildings.has(building);
          return (
            <Card key={building} title={humanizeBuilding(building)}>
              {!built ? (
                <p className="muted">Building doesn't exist yet.</p>
              ) : entries.length === 0 ? (
                <p className="muted">No trainable units in the catalogue for this tribe.</p>
              ) : (
                <table className="table table--compact">
                  <thead>
                    <tr>
                      <th>Unit</th>
                      <th style={{ width: '8rem' }}>Min units</th>
                      <th style={{ width: '8rem' }}>Max units</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry) => {
                      const cur = rows[entry.code] ?? { minQty: 0, maxQty: 0 };
                      return (
                        <tr key={entry.code}>
                          <td>{entry.displayName}</td>
                          <td>
                            <input
                              type="number"
                              min={0}
                              value={cur.minQty}
                              onChange={(e) =>
                                setRow(entry.code, { minQty: clampInt(e.target.value, 0, 1_000_000) })
                              }
                              disabled={!cycle.enabled}
                              style={{ width: '100%' }}
                            />
                          </td>
                          <td>
                            <input
                              type="number"
                              min={0}
                              value={cur.maxQty}
                              onChange={(e) =>
                                setRow(entry.code, { maxQty: clampInt(e.target.value, 0, 1_000_000) })
                              }
                              disabled={!cycle.enabled}
                              style={{ width: '100%' }}
                            />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </Card>
          );
        })
      )}

      <div className="settings-savebar">
        <div className="settings-savebar__msg">
          {isDirty && (
            <span className="muted" style={{ fontSize: '0.8rem' }}>
              ● Unsaved changes
            </span>
          )}
          {saveState.kind === 'saving' && <span className="muted">Saving…</span>}
          {saveState.kind === 'saved' && !isDirty && <span className="pill pill--success">Saved</span>}
          {saveState.kind === 'error' && (
            <span className="form__msg form__msg--error" style={{ margin: 0 }}>
              {saveState.message}
            </span>
          )}
        </div>
        <button
          type="button"
          className="btn btn--primary"
          onClick={save}
          disabled={!isDirty || saveState.kind === 'saving'}
        >
          {saveState.kind === 'saving' ? 'Saving…' : 'Save training'}
        </button>
      </div>
    </div>
  );
}

function humanizeBuilding(b: TroopBuilding): string {
  switch (b) {
    case 'BARRACKS': return 'Barracks';
    case 'STABLE': return 'Stable';
    case 'WORKSHOP': return 'Workshop';
  }
}

function clampInt(raw: string, min: number, max: number): number {
  const n = Number.parseInt(raw, 10);
  if (Number.isNaN(n)) return min;
  return Math.max(min, Math.min(max, n));
}
