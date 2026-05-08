import { useEffect, useState } from 'react';
import Card from '../../components/Card';
import { villageSettingsApi } from '../../api/settings';
import type { VillageSettings, VillageSettingsUpdate } from '../../types/villageSettings';

/**
 * Per-village settings tab — NPC trader config + crop watcher + auto-scrape
 * toggle. NPC used to be account-wide; it moved here because different
 * villages on the same account need different NPC behaviour (a capital
 * feeding a wonder wants aggressive NPC; a farm village shouldn't trade at
 * all). Crop watcher is a standalone "rescue before starvation" trigger that
 * fires whenever the granary drops below the threshold, independent of the
 * regular NPC rules.
 */
interface Props {
  villageId: number;
}

type Draft = Omit<VillageSettings, 'villageId' | 'lastScrapedAt'>;
type LoadState = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; message: string };
type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

/**
 * Three resource-only presets the user picked — all leave crop at 0 because
 * crop is handled separately by the Crop watcher. Tuples are wood / clay / iron.
 */
const NPC_PRESETS: Array<{ label: string; values: [number, number, number] }> = [
  { label: '33 / 34 / 33', values: [33, 34, 33] },
  { label: '35 / 45 / 20', values: [35, 45, 20] },
  { label: '30 / 40 / 30', values: [30, 40, 30] },
];

export default function VillageSettingsTab({ villageId }: Props) {
  const [loadState, setLoadState] = useState<LoadState>({ kind: 'loading' });
  const [saveState, setSaveState] = useState<SaveState>({ kind: 'idle' });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [lastScrapedAt, setLastScrapedAt] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadState({ kind: 'loading' });
    villageSettingsApi.get(villageId)
      .then((s) => {
        if (cancelled) return;
        const { villageId: _id, lastScrapedAt: scraped, ...rest } = s;
        void _id;
        setDraft(rest);
        setLastScrapedAt(scraped);
        setLoadState({ kind: 'ready' });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const message = err instanceof Error ? err.message : 'Failed to load settings';
        setLoadState({ kind: 'error', message });
      });
    return () => { cancelled = true; };
  }, [villageId]);

  if (loadState.kind === 'loading') return <Card><p className="muted">Loading village settings…</p></Card>;
  if (loadState.kind === 'error') {
    return <Card><p className="form__msg form__msg--error">{loadState.message}</p></Card>;
  }
  if (!draft) return null;

  const patch = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((d) => (d ? { ...d, [key]: value } : d));

  const ratioSum = draft.npcRatioWood + draft.npcRatioClay + draft.npcRatioIron + draft.npcRatioCrop;
  const ratiosInvalid = draft.npcEnabled && ratioSum !== 100;

  /**
   * Apply one of the {@link NPC_PRESETS}. Crop stays at 0 — crop is owned by
   * the Crop watcher card below; lumping it into the regular ratios just
   * causes confusion when the watcher fires.
   */
  const applyPreset = (values: [number, number, number]) => {
    setDraft((d) =>
      d
        ? {
            ...d,
            npcRatioWood: values[0],
            npcRatioClay: values[1],
            npcRatioIron: values[2],
            npcRatioCrop: 0,
          }
        : d,
    );
  };

  const save = async () => {
    if (!draft) return;
    if (ratiosInvalid) {
      setSaveState({ kind: 'error', message: `NPC ratios must sum to 100 (currently ${ratioSum}).` });
      return;
    }
    setSaveState({ kind: 'saving' });
    const body: VillageSettingsUpdate = { ...draft };
    try {
      const saved = await villageSettingsApi.update(villageId, body);
      const { villageId: _id, lastScrapedAt: scraped, ...rest } = saved;
      void _id;
      setDraft(rest);
      setLastScrapedAt(scraped);
      setSaveState({ kind: 'saved' });
    } catch (err) {
      setSaveState({ kind: 'error', message: err instanceof Error ? err.message : 'Save failed' });
    }
  };

  return (
    <div className="stack stack--xwide">
      <Card title="Scraping">
        <div className="form form--compact">
          <label className="form__check form__field--full">
            <input
              type="checkbox"
              checked={draft.autoScrapeEnabled}
              onChange={(e) => patch('autoScrapeEnabled', e.target.checked)}
            />
            <span>Include this village in the auto-scrape cycle</span>
          </label>
          <div className="form__field">
            <label>Scrape interval (minutes)</label>
            <div className="form__inline">
              <input
                type="number"
                min={1}
                max={1440}
                value={draft.autoScrapeIntervalMinutes}
                onChange={(e) => patch('autoScrapeIntervalMinutes', clampInt(e.target.value, 1, 1440))}
                disabled={!draft.autoScrapeEnabled}
                style={{ width: '6rem' }}
              />
              <span className="muted" style={{ fontSize: '0.85rem' }}>
                Last scraped: {lastScrapedAt ? new Date(lastScrapedAt).toLocaleString() : 'never'}
              </span>
            </div>
          </div>
        </div>
      </Card>

      <Card title="NPC trader">
        <div className="form form--compact">
          <label className="form__check form__field--full">
            <input
              type="checkbox"
              checked={draft.npcEnabled}
              onChange={(e) => patch('npcEnabled', e.target.checked)}
            />
            <span>Enable auto-NPC for this village</span>
          </label>

          <div className="form__field">
            <label>Trigger at granary (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              value={draft.npcTriggerPct}
              onChange={(e) => patch('npcTriggerPct', clampInt(e.target.value, 0, 100))}
            />
          </div>

          <div className="form__field">
            <label>Min gold reserve</label>
            <input
              type="number"
              min={0}
              value={draft.goldReserve}
              onChange={(e) => patch('goldReserve', clampInt(e.target.value, 0, 100_000))}
            />
          </div>

          <div className="form__field form__field--full">
            <label>Ratio presets</label>
            <div className="form__inline" style={{ flexWrap: 'wrap' }}>
              {NPC_PRESETS.map((p) => (
                <button
                  key={p.label}
                  type="button"
                  className="btn btn--ghost btn--sm"
                  onClick={() => applyPreset(p.values)}
                  title={`Set Wood / Clay / Iron to ${p.label} and Crop to 0`}
                >
                  {p.label}
                </button>
              ))}
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                Presets fill wood / clay / iron and leave crop at 0%.
              </span>
            </div>
          </div>

          <div className="form__grid form__field--full">
            <RatioInput label="Wood %" value={draft.npcRatioWood} onChange={(v) => patch('npcRatioWood', v)} />
            <RatioInput label="Clay %" value={draft.npcRatioClay} onChange={(v) => patch('npcRatioClay', v)} />
            <RatioInput label="Iron %" value={draft.npcRatioIron} onChange={(v) => patch('npcRatioIron', v)} />
            <RatioInput label="Crop %" value={draft.npcRatioCrop} onChange={(v) => patch('npcRatioCrop', v)} />
          </div>
          {ratiosInvalid && (
            <p className="form__msg form__msg--error" style={{ margin: 0 }}>
              Ratios must sum to 100 when NPC is enabled (currently {ratioSum}).
            </p>
          )}
        </div>
      </Card>

      <Card title="Crop watcher">
        <div className="form form--compact">
          <label className="form__check form__field--full">
            <input
              type="checkbox"
              checked={draft.cropWatcherEnabled}
              onChange={(e) => patch('cropWatcherEnabled', e.target.checked)}
            />
            <span>
              Auto-NPC every other resource into crop when the granary drops below the threshold
            </span>
          </label>

          <div className="form__field">
            <label>Threshold (% of granary)</label>
            <input
              type="number"
              min={0}
              max={100}
              value={draft.cropWatcherThresholdPct}
              onChange={(e) => patch('cropWatcherThresholdPct', clampInt(e.target.value, 0, 100))}
              disabled={!draft.cropWatcherEnabled}
            />
          </div>

          <div className="form__field">
            <label>Min gold reserve</label>
            <input
              type="number"
              min={0}
              value={draft.cropWatcherMinGold}
              onChange={(e) => patch('cropWatcherMinGold', clampInt(e.target.value, 0, 100_000))}
              disabled={!draft.cropWatcherEnabled}
            />
          </div>
        </div>
      </Card>

      <div className="settings-savebar">
        <div className="settings-savebar__msg">
          {saveState.kind === 'saving' && <span className="muted">Saving…</span>}
          {saveState.kind === 'saved' && <span className="pill pill--success">Saved</span>}
          {saveState.kind === 'error' && (
            <span className="form__msg form__msg--error" style={{ margin: 0 }}>
              {saveState.message}
            </span>
          )}
        </div>
        <button type="button" className="btn btn--primary" onClick={save} disabled={saveState.kind === 'saving' || ratiosInvalid}>
          Save village settings
        </button>
      </div>
    </div>
  );
}

function RatioInput({ label, value, onChange }: { label: string; value: number; onChange: (v: number) => void }) {
  return (
    <div className="form__field">
      <label>{label}</label>
      <input
        type="number"
        min={0}
        max={100}
        value={value}
        onChange={(e) => onChange(clampInt(e.target.value, 0, 100))}
      />
    </div>
  );
}

function clampInt(raw: string, min: number, max: number): number {
  const n = Number.parseInt(raw, 10);
  if (Number.isNaN(n)) return min;
  return Math.max(min, Math.min(max, n));
}
