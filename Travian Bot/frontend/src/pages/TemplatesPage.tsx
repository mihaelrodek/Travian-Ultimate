import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { templatesApi } from '../api/templates';
import { villagesApi } from '../api/villages';
import type {
  TemplateCategory,
  TemplateStep,
  VillageTemplate,
} from '../types/template';
import { TEMPLATE_CATEGORIES, TEMPLATE_MACROS } from '../types/template';
import { BUILDING_TYPES } from '../types/village';
import type { Village } from '../types/village';

/**
 * Village templates — reusable ordered build plans ("Cap Offense",
 * "Defensive crop village", etc.) scoped to an account.
 * <p>
 * The page is a master / detail. Templates are applied to a village
 * (appending onto its build queue) via the Apply button. The slot for each
 * step is decided by the backend at apply time (existing building reused,
 * else first empty infra slot, else first matching resource field) — the
 * editor here doesn't ask the user to pick slots.
 *
 * <h3>Macros</h3>
 * Resource fields are queued as a group via macro types ALL_WOOD / ALL_CLAY
 * / ALL_IRON / ALL_CROP / ALL_RESOURCES / ALL_RESOURCES_NO_CROP. The backend
 * expands each macro into one queue row per matching field at apply time.
 *
 * <h3>Auto-bump target level</h3>
 * When the same building type appears more than once in a template, the
 * editor enforces a per-step minimum target level of "max target of any
 * earlier step with this type, plus one". Adding a fresh row defaults to
 * that minimum; the user can type a higher number but not a lower one.
 */
export default function TemplatesPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [templates, setTemplates] = useState<VillageTemplate[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [villages, setVillages] = useState<Village[]>([]);

  // Local editable copy of the selected template — null when the user
  // hasn't picked one yet, or when the selection was deleted. A separate
  // "creating" flag distinguishes unsaved-new from unsaved-edit so Save
  // can pick POST vs PUT without heuristics.
  const [draft, setDraft] = useState<EditableTemplate | null>(null);
  // Snapshot of the draft as it was last persisted (or first loaded). Used
  // to detect unsaved changes — a deep JSON compare of draft vs snapshot
  // tells us whether the user has edits in flight. Distinct from `draft`
  // so changing the draft doesn't accidentally "wipe" the diff.
  const [savedSnapshot, setSavedSnapshot] = useState<EditableTemplate | null>(null);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [banner, setBanner] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  // True when the draft has uncommitted changes vs the last saved snapshot.
  // Drives the orange Save dot, the leave-page guard, and the browser's own
  // beforeunload prompt for tab-close. Cheap deep-equal via JSON since the
  // draft shape is small.
  const isDirty = useMemo(() => {
    if (!draft) return false;
    if (!savedSnapshot) return true; // brand-new unsaved template
    return JSON.stringify(draft) !== JSON.stringify(savedSnapshot);
  }, [draft, savedSnapshot]);

  // Browser-level "you have unsaved changes" prompt for tab close / refresh.
  // Doesn't catch in-app navigation (React Router doesn't trigger it) — the
  // in-app guard is handled by intercepting the sidebar / library clicks
  // below. This still protects the user from a literal close-the-tab.
  useEffect(() => {
    if (!isDirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      // Modern browsers ignore the actual string and show their own copy,
      // but returnValue must be assigned for the prompt to fire at all.
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [isDirty]);

  /**
   * Confirmation guard before leaving the current draft. Returns true when
   * it's safe to proceed (no unsaved changes, or the user accepted the
   * prompt); returns false when the caller should bail out and keep the
   * draft on screen.
   */
  const confirmDiscardIfDirty = (): boolean => {
    if (!isDirty) return true;
    return window.confirm(
      'You have unsaved changes to this template. Discard them and continue?',
    );
  };

  const [csvText, setCsvText] = useState('');
  const [csvName, setCsvName] = useState('');
  const [csvCategory, setCsvCategory] = useState<TemplateCategory>('OFFENSE');
  const [csvOpen, setCsvOpen] = useState(false);

  const accountRef = useRef<number | null>(accountId);
  accountRef.current = accountId;

  // Load templates + villages whenever the account changes.
  useEffect(() => {
    if (accountId == null) {
      setTemplates([]);
      setVillages([]);
      setSelectedId(null);
      setDraft(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    Promise.all([
      templatesApi.list(accountId).catch(() => [] as VillageTemplate[]),
      villagesApi.list(accountId).catch(() => [] as Village[]),
    ])
      .then(([tpl, vs]) => {
        if (cancelled || accountRef.current !== accountId) return;
        setTemplates(tpl);
        setVillages(vs);
        setError(null);
        // Preserve selection if it survived the reload; otherwise clear.
        setSelectedId((prev) => (prev != null && tpl.some((t) => t.id === prev) ? prev : null));
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load templates');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  // Sync draft to the selected template; if the user was mid-create and
  // picked a saved template, drop the in-progress create. Snapshot the
  // freshly-loaded version so the dirty check has a baseline.
  useEffect(() => {
    if (selectedId == null) {
      if (!creating) {
        setDraft(null);
        setSavedSnapshot(null);
      }
      return;
    }
    const t = templates.find((x) => x.id === selectedId);
    if (t) {
      const loaded = fromTemplate(t);
      setDraft(loaded);
      setSavedSnapshot(loaded);
      setCreating(false);
    }
  }, [selectedId, templates, creating]);

  /**
   * Building options the editor surfaces — macros first (the bulk operations
   * the user explicitly added), then real infra buildings. Single resource
   * fields (WOODCUTTER / CLAY_PIT / IRON_MINE / CROPLAND) are deliberately
   * excluded: bulk macros cover that case and a single-field upgrade in a
   * template is almost always a mistake (which of the four wood fields?).
   */
  const buildingOptions = useMemo<string[]>(() => {
    const infra = BUILDING_TYPES.filter(
      (t) => !['WOODCUTTER', 'CLAY_PIT', 'IRON_MINE', 'CROPLAND'].includes(t),
    );
    return [...TEMPLATE_MACROS, ...infra];
  }, []);

  /**
   * Minimum target level the user is allowed to set on the row at index
   * {@code idx}. Equals max(targetLevel of earlier steps with the same
   * buildingType) + 1, or 1 if this is the first occurrence. Drives both the
   * default level on add and the input min on edit.
   */
  const minLevelFor = (steps: TemplateStep[], idx: number, buildingType: string): number => {
    let max = 0;
    for (let i = 0; i < idx; i++) {
      if (steps[i].buildingType === buildingType && steps[i].targetLevel > max) {
        max = steps[i].targetLevel;
      }
    }
    return max + 1;
  };

  const onNew = () => {
    if (!confirmDiscardIfDirty()) return;
    setSelectedId(null);
    setCreating(true);
    setDraft({
      id: null,
      name: '',
      description: '',
      category: 'OFFENSE',
      steps: [],
    });
    // No snapshot for a brand-new template — isDirty will report true as
    // soon as the user types anything (or even immediately, since "blank
    // template" is itself a dirty state vs "no draft at all").
    setSavedSnapshot(null);
    setBanner(null);
  };

  const onSelect = (id: number) => {
    if (id === selectedId) return;
    if (!confirmDiscardIfDirty()) return;
    setSelectedId(id);
    setCreating(false);
    setBanner(null);
  };

  const onDelete = async () => {
    if (draft?.id == null) return;
    if (!window.confirm(`Delete template "${draft.name}"?`)) return;
    try {
      await templatesApi.delete(draft.id);
      setTemplates((prev) => prev.filter((t) => t.id !== draft.id));
      setDraft(null);
      setSelectedId(null);
      setBanner({ kind: 'ok', text: 'Template deleted.' });
    } catch (err) {
      setBanner({ kind: 'err', text: err instanceof Error ? err.message : 'Delete failed' });
    }
  };

  const onSave = async () => {
    if (!draft) return;
    if (accountId == null) {
      // Defensive — the toolbar sits inside an `accountId != null` guard, so
      // this only fires if the user managed to deactivate the scoped account
      // mid-edit. Without an accountId the backend will 400 on POST anyway,
      // so we surface a clear message instead of letting it through.
      setBanner({ kind: 'err', text: 'No active account — pick one on the Accounts page first.' });
      return;
    }
    if (!draft.name.trim()) {
      setBanner({ kind: 'err', text: 'Name is required.' });
      return;
    }
    setSaving(true);
    setBanner(null);
    try {
      let saved: VillageTemplate;
      if (draft.id == null) {
        saved = await templatesApi.create({
          accountId,
          name: draft.name.trim(),
          description: draft.description,
          category: draft.category,
          steps: draft.steps,
        });
      } else {
        saved = await templatesApi.update(draft.id, {
          name: draft.name.trim(),
          description: draft.description,
          category: draft.category,
          steps: draft.steps,
        });
      }
      // Belt-and-braces: re-fetch the canonical list from the backend right
      // after save instead of trusting our optimistic local update. Earlier
      // bug reports said templates "disappeared" between sessions — the
      // server-truth refresh removes any chance of a stale local mirror
      // hiding a row that was actually persisted, and surfaces an empty
      // list immediately if the save somehow didn't land.
      let fresh: VillageTemplate[];
      try {
        fresh = await templatesApi.list(accountId);
      } catch {
        // Network blip on the refresh shouldn't undo a successful save —
        // fall back to the optimistic merge so the row still shows up.
        fresh = draft.id == null
          ? [saved, ...templates]
          : templates.map((t) => (t.id === saved.id ? saved : t));
      }
      setTemplates(fresh);
      // Use the just-saved row from the freshly-fetched list when present —
      // covers the edge case where a server-side validator normalised the
      // payload (trimmed whitespace, etc.) so the snapshot matches what the
      // backend actually persisted.
      const canonical = fresh.find((t) => t.id === saved.id) ?? saved;
      const loaded = fromTemplate(canonical);
      setDraft(loaded);
      setSavedSnapshot(loaded);
      setSelectedId(saved.id);
      setCreating(false);
      setBanner({ kind: 'ok', text: 'Template saved.' });
    } catch (err) {
      setBanner({ kind: 'err', text: err instanceof Error ? err.message : 'Save failed' });
    } finally {
      setSaving(false);
    }
  };

  const onAddStep = () => {
    if (!draft) return;
    // Default to the first option (a macro) — pick a meaningful initial type
    // and seed the level to the auto-bump minimum so the user only types if
    // they want to override.
    const defaultType = buildingOptions[0];
    const min = minLevelFor(draft.steps, draft.steps.length, defaultType);
    setDraft({
      ...draft,
      steps: [
        ...draft.steps,
        { id: null, buildingType: defaultType, slot: null, targetLevel: min },
      ],
    });
  };

  const onChangeStep = (idx: number, patch: Partial<TemplateStep>) => {
    if (!draft) return;
    setDraft((d) => {
      if (!d) return d;
      const next = d.steps.map((s, i) => (i === idx ? { ...s, ...patch } : s));
      // If the building type was changed, snap the target level up to the
      // new minimum (max prior level for this type + 1) so the user can't
      // accidentally regress below the auto-bump rule.
      if (patch.buildingType !== undefined) {
        const min = minLevelFor(next, idx, patch.buildingType);
        if (next[idx].targetLevel < min) {
          next[idx] = { ...next[idx], targetLevel: min };
        }
      }
      return { ...d, steps: next };
    });
  };

  const onMoveStep = (idx: number, delta: -1 | 1) => {
    if (!draft) return;
    const j = idx + delta;
    if (j < 0 || j >= draft.steps.length) return;
    const next = draft.steps.slice();
    [next[idx], next[j]] = [next[j], next[idx]];
    setDraft({ ...draft, steps: next });
  };

  const onRemoveStep = (idx: number) => {
    if (!draft) return;
    setDraft({ ...draft, steps: draft.steps.filter((_, i) => i !== idx) });
  };

  const onApplyToVillage = async (villageId: number) => {
    if (draft?.id == null) return;
    try {
      const res = await templatesApi.apply(draft.id, villageId);
      setBanner({ kind: 'ok', text: `Applied — added ${res.added} step(s) to the village queue.` });
    } catch (err) {
      setBanner({ kind: 'err', text: err instanceof Error ? err.message : 'Apply failed' });
    }
  };

  const onImportCsv = async () => {
    if (accountId == null) return;
    if (!csvName.trim() || !csvText.trim()) {
      setBanner({ kind: 'err', text: 'Name and CSV body are required.' });
      return;
    }
    try {
      const saved = await templatesApi.importCsv({
        accountId,
        name: csvName.trim(),
        description: '',
        category: csvCategory,
        csv: csvText,
      });
      setTemplates((prev) => [saved, ...prev]);
      setSelectedId(saved.id);
      setCreating(false);
      setCsvOpen(false);
      setCsvText('');
      setCsvName('');
      setBanner({ kind: 'ok', text: `Imported "${saved.name}" with ${saved.steps.length} step(s).` });
    } catch (err) {
      setBanner({ kind: 'err', text: err instanceof Error ? err.message : 'Import failed' });
    }
  };

  const onExportCsv = async () => {
    if (draft?.id == null) return;
    try {
      const body = await templatesApi.exportCsv(draft.id);
      await navigator.clipboard.writeText(body).catch(() => undefined);
      // Also offer a download — saves the user rummaging through clipboard
      // managers and means the same button works if clipboard access is
      // blocked by the browser.
      const blob = new Blob([body], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${slug(draft.name || 'template')}.csv`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      setBanner({ kind: 'ok', text: 'Copied CSV to clipboard and downloaded a file.' });
    } catch (err) {
      setBanner({ kind: 'err', text: err instanceof Error ? err.message : 'Export failed' });
    }
  };

  // ---- render ---------------------------------------------------------

  if (accountId == null && !accountsLoading) {
    return (
      <div className="stack stack--xwide">
        <div>
          <h1 className="page__title">Templates</h1>
          <p className="page__subtitle">Reusable build plans for your villages.</p>
        </div>
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to use this screen.
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack stack--xwide">
      <div className="page__header">
        <div>
          <h1 className="page__title">Templates</h1>
          <p className="page__subtitle">
            Named build plans you can apply to any village on this account. Slots are picked
            automatically at apply time — first the existing building, then the first empty
            slot, then matching resource fields for ALL_* macros.
          </p>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => setCsvOpen((o) => !o)}>
            {csvOpen ? 'Close import' : 'Import CSV'}
          </button>
          <button type="button" className="btn btn--primary btn--sm" onClick={onNew}>
            + New template
          </button>
        </div>
      </div>

      {banner && (
        <p className={`form__msg ${banner.kind === 'ok' ? 'form__msg--success' : 'form__msg--error'}`}>
          {banner.text}
        </p>
      )}
      {error && <p className="form__msg form__msg--error">{error}</p>}

      {csvOpen && (
        <Card title="Import template from CSV">
          <p className="muted" style={{ marginBottom: '0.5rem' }}>
            One step per line: <code>buildingType,slot,targetLevel</code>. The slot column
            is optional — the backend will pick a slot at apply time if you leave it blank.
            Lines starting with <code>#</code> are ignored.
          </p>
          <form className="form form--compact" onSubmit={(e) => e.preventDefault()}>
            <label className="form__field">
              <span>Name</span>
              <input
                type="text"
                value={csvName}
                onChange={(e) => setCsvName(e.target.value)}
                maxLength={120}
                placeholder="e.g. Cap Offense"
              />
            </label>
            <label className="form__field">
              <span>Category</span>
              <select value={csvCategory} onChange={(e) => setCsvCategory(e.target.value as TemplateCategory)}>
                {TEMPLATE_CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </label>
          </form>
          <textarea
            value={csvText}
            onChange={(e) => setCsvText(e.target.value)}
            rows={8}
            style={{ width: '100%', fontFamily: 'monospace' }}
            placeholder={'MAIN_BUILDING,,20\nALL_WOOD,,8\nALL_RESOURCES_NO_CROP,,5\nRESIDENCE,,10'}
          />
          <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
            <button type="button" className="btn btn--primary btn--sm" onClick={onImportCsv}>
              Import
            </button>
            <button type="button" className="btn btn--ghost btn--sm" onClick={() => setCsvOpen(false)}>
              Cancel
            </button>
          </div>
        </Card>
      )}

      <div className="split-pane" style={{ display: 'grid', gridTemplateColumns: '18rem 1fr', gap: '1rem' }}>
        <Card title={`Library (${templates.length})`}>
          {loading && <p className="muted">Loading…</p>}
          {!loading && templates.length === 0 && (
            <p className="muted">No templates yet. Create one or import a CSV.</p>
          )}
          {!loading && templates.length > 0 && (
            <ul className="sidebar__sublist" style={{ paddingLeft: 0, listStyle: 'none' }}>
              {templates.map((t) => {
                const active = t.id === selectedId;
                return (
                  <li key={t.id}>
                    <button
                      type="button"
                      onClick={() => onSelect(t.id)}
                      className={`sidebar__link${active ? ' sidebar__link--active' : ''}`}
                      style={{ width: '100%', textAlign: 'left' }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '0.5rem' }}>
                        <span>{t.name}</span>
                        <span className="pill pill--neutral">{t.category}</span>
                      </div>
                      <div className="muted" style={{ fontSize: '0.8rem' }}>
                        {t.steps.length} step(s) · {new Date(t.updatedAt).toLocaleDateString()}
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>

        <Card title={draft ? (draft.id == null ? 'New template' : draft.name || 'Untitled') : 'Template'}>
          {!draft && <p className="muted">Pick a template on the left, or click <strong>+ New template</strong>.</p>}

          {draft && (
            <>
              <form className="form form--compact" onSubmit={(e) => e.preventDefault()}>
                <label className="form__field">
                  <span>Name</span>
                  <input
                    type="text"
                    value={draft.name}
                    onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                    maxLength={120}
                  />
                </label>
                <label className="form__field">
                  <span>Category</span>
                  <select
                    value={draft.category}
                    onChange={(e) => setDraft({ ...draft, category: e.target.value as TemplateCategory })}
                  >
                    {TEMPLATE_CATEGORIES.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="form__field form__field--full">
                  <span>Description</span>
                  <textarea
                    value={draft.description}
                    onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                    rows={2}
                    maxLength={1000}
                  />
                </label>
              </form>

              <div className="toolbar" style={{ marginTop: '0.75rem' }}>
                <button type="button" className="btn btn--ghost btn--sm" onClick={onAddStep}>
                  + Add step
                </button>
                <span style={{ flex: 1 }} />
                {isDirty && (
                  <span
                    className="muted"
                    style={{ fontSize: '0.8rem' }}
                    title="Unsaved changes — click Save to persist them."
                  >
                    ● Unsaved changes
                  </span>
                )}
                {draft.id != null && (
                  <>
                    <button type="button" className="btn btn--ghost btn--sm" onClick={onExportCsv}>
                      Export CSV
                    </button>
                    <button type="button" className="btn btn--ghost btn--sm" onClick={onDelete}>
                      Delete
                    </button>
                  </>
                )}
                <button
                  type="button"
                  className="btn btn--primary btn--sm"
                  onClick={onSave}
                  disabled={saving || !isDirty}
                  title={isDirty ? 'Save your changes' : 'Already up to date'}
                >
                  {saving ? 'Saving…' : isDirty ? 'Save' : 'Saved'}
                </button>
              </div>

              {draft.steps.length === 0 && (
                <p className="muted" style={{ marginTop: '0.5rem' }}>
                  No steps yet — click <strong>+ Add step</strong> to build the plan.
                </p>
              )}

              {draft.steps.length > 0 && (
                <table className="table">
                  <thead>
                    <tr>
                      <th style={{ width: '3rem' }}>#</th>
                      <th>Building</th>
                      <th style={{ width: '8rem' }}>Target level</th>
                      <th style={{ width: '6rem' }} />
                    </tr>
                  </thead>
                  <tbody>
                    {draft.steps.map((s, idx) => (
                      <StepRow
                        key={s.id ?? `new-${idx}`}
                        step={s}
                        idx={idx}
                        options={buildingOptions}
                        minLevel={minLevelFor(draft.steps, idx, s.buildingType)}
                        onChange={(patch) => onChangeStep(idx, patch)}
                        onMoveUp={() => onMoveStep(idx, -1)}
                        onMoveDown={() => onMoveStep(idx, +1)}
                        onRemove={() => onRemoveStep(idx)}
                      />
                    ))}
                  </tbody>
                </table>
              )}

              {draft.id != null && villages.length > 0 && (
                <div style={{ marginTop: '1rem', paddingTop: '0.75rem', borderTop: '1px solid var(--border, #2b2f36)' }}>
                  <h3 style={{ margin: '0 0 0.5rem' }}>Apply to village</h3>
                  <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.85rem' }}>
                    Appends this template's steps to the village's build queue. Existing queue items
                    are untouched. Slots are auto-assigned per village.
                  </p>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                    {villages.map((v) => (
                      <button
                        key={v.id}
                        type="button"
                        className="btn btn--ghost btn--sm"
                        onClick={() => onApplyToVillage(v.id)}
                      >
                        → {v.name} ({v.coordinateX}|{v.coordinateY})
                        {v.capital ? ' ★' : ''}
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

interface EditableTemplate {
  id: number | null;
  name: string;
  description: string;
  category: TemplateCategory;
  steps: TemplateStep[];
}

function fromTemplate(t: VillageTemplate): EditableTemplate {
  return {
    id: t.id,
    name: t.name,
    description: t.description ?? '',
    category: t.category,
    steps: t.steps.map((s) => ({ ...s, slot: null })),
  };
}

function slug(s: string): string {
  return s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'template';
}

function StepRow({
  step,
  idx,
  options,
  minLevel,
  onChange,
  onMoveUp,
  onMoveDown,
  onRemove,
}: {
  step: TemplateStep;
  idx: number;
  options: string[];
  minLevel: number;
  onChange: (patch: Partial<TemplateStep>) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onRemove: () => void;
}) {
  return (
    <tr>
      <td className="muted">{idx + 1}</td>
      <td>
        <select
          value={step.buildingType}
          onChange={(e) => onChange({ buildingType: e.target.value })}
          style={{ width: '100%' }}
        >
          {options.map((b) => (
            <option key={b} value={b}>
              {humanize(b)}
            </option>
          ))}
          {/* Tolerate legacy types that aren't in the current option list
              (e.g. WOODCUTTER from a CSV import). The user can pick a new
              type from the dropdown to migrate. */}
          {!options.includes(step.buildingType) && (
            <option value={step.buildingType}>{step.buildingType}</option>
          )}
        </select>
      </td>
      <td>
        <input
          type="number"
          min={minLevel}
          max={25}
          value={step.targetLevel}
          onChange={(e) => {
            const raw = Math.max(0, Math.min(25, Math.floor(Number(e.target.value) || 0)));
            onChange({ targetLevel: Math.max(minLevel, raw) });
          }}
          title={
            minLevel > 1
              ? `Auto-bumped: another ${humanize(step.buildingType)} step queues to L${minLevel - 1}, so this one starts at L${minLevel}.`
              : undefined
          }
          style={{ width: '5rem' }}
        />
      </td>
      <td>
        <div style={{ display: 'flex', gap: '0.25rem' }}>
          <button type="button" className="btn btn--ghost btn--sm" onClick={onMoveUp} title="Move up">
            ↑
          </button>
          <button type="button" className="btn btn--ghost btn--sm" onClick={onMoveDown} title="Move down">
            ↓
          </button>
          <button type="button" className="btn btn--ghost btn--sm" onClick={onRemove} title="Remove">
            ✕
          </button>
        </div>
      </td>
    </tr>
  );
}

function humanize(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
    .join(' ');
}
