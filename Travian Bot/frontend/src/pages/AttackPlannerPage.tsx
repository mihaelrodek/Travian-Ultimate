import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import GlassHeader from '../components/GlassHeader';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { attackPlansApi } from '../api/attackPlans';
import { villagesApi } from '../api/villages';
import type {
  AttackPlan,
  AttackPlanMatrix,
  AttackPlanRequest,
  MatrixCell,
  MatrixSourceRow,
  MatrixTargetRow,
} from '../types/attackPlan';
import type { Village } from '../types/village';

/**
 * War-op planner. Three-pane layout once a plan is selected:
 *   1. Left: list of saved plans (create / pick / delete)
 *   2. Middle: plan editor — targets list + sources picker + landing/speed meta
 *   3. Bottom: travel-time matrix (source × target) with launch times
 *
 * Mass-paste for targets accepts one coord pair per line:
 *   -47|123  My label
 *   (12|-9)  Hammer
 *   100,-5
 * Numbers + optional surrounding parens, X|Y or X,Y separator, optional
 * trailing label. Lines that don't match are silently ignored — the user
 * sees the parsed count before save.
 */
export default function AttackPlannerPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [plans, setPlans] = useState<AttackPlan[]>([]);
  const [villages, setVillages] = useState<Village[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());
  const [matrix, setMatrix] = useState<AttackPlanMatrix | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [matrixLoading, setMatrixLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  const reload = useCallback(async () => {
    if (accountId == null) {
      setPlans([]);
      setVillages([]);
      setSelectedId(null);
      setEditor(emptyEditor());
      setMatrix(null);
      return;
    }
    setLoading(true);
    try {
      const [p, v] = await Promise.all([
        attackPlansApi.list(accountId),
        villagesApi.list(accountId),
      ]);
      setPlans(p);
      setVillages(v);
      setError(null);
      if (selectedId == null && p.length > 0) {
        setSelectedId(p[0].id);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load plans');
    } finally {
      setLoading(false);
    }
  }, [accountId, selectedId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // When the selection changes, hydrate the editor + matrix from the chosen plan.
  useEffect(() => {
    if (selectedId == null) {
      setEditor(emptyEditor());
      setMatrix(null);
      return;
    }
    const plan = plans.find((p) => p.id === selectedId);
    if (!plan) return;
    setEditor(planToEditor(plan));
    void refreshMatrix(plan.id);
    // refreshMatrix is closed over accountId — safe to omit from deps because
    // we want this to fire on plan selection only, not on every accountId tick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId, plans]);

  const refreshMatrix = async (planId: number) => {
    if (accountId == null) return;
    setMatrixLoading(true);
    try {
      const m = await attackPlansApi.matrix(accountId, planId);
      setMatrix(m);
    } catch (err) {
      console.warn('Matrix load failed', err);
      setMatrix(null);
    } finally {
      setMatrixLoading(false);
    }
  };

  const onNewPlan = async () => {
    if (accountId == null) return;
    const name = window.prompt('New plan name', 'Saturday op');
    if (!name || !name.trim()) return;
    setSaving(true);
    try {
      const created = await attackPlansApi.create(accountId, {
        name: name.trim(),
        speedFieldsPerHour: 14,
        landingAt: null,
        targets: [],
        sourceVillageIds: [],
      });
      setPlans((prev) => [created, ...prev]);
      setSelectedId(created.id);
      setFlash({ kind: 'ok', text: `Created "${created.name}"` });
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Create failed',
      });
    } finally {
      setSaving(false);
    }
  };

  const onSave = async () => {
    if (accountId == null || selectedId == null) return;
    const req = editorToRequest(editor);
    if (!req.name || req.name.trim() === '') {
      setFlash({ kind: 'err', text: 'Plan name is required.' });
      return;
    }
    setSaving(true);
    try {
      const updated = await attackPlansApi.update(accountId, selectedId, req);
      setPlans((prev) => prev.map((p) => (p.id === updated.id ? updated : p)));
      setEditor(planToEditor(updated));
      await refreshMatrix(updated.id);
      setFlash({ kind: 'ok', text: 'Saved.' });
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Save failed',
      });
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async () => {
    if (accountId == null || selectedId == null) return;
    const plan = plans.find((p) => p.id === selectedId);
    if (!plan) return;
    if (!window.confirm(`Delete plan "${plan.name}"?`)) return;
    setSaving(true);
    try {
      await attackPlansApi.delete(accountId, selectedId);
      setPlans((prev) => prev.filter((p) => p.id !== selectedId));
      setSelectedId(null);
      setFlash({ kind: 'ok', text: 'Deleted.' });
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Delete failed',
      });
    } finally {
      setSaving(false);
    }
  };

  if (accountId == null && !accountsLoading) {
    return (
      <div className="stack">
        <GlassHeader
        title="Planer napada"
        subtitle="Mete + napadačka sela + matrica vremena dolaska."
      />
        <Card>
          <p className="muted">
            Pick an account from the{' '}
            <Link to="/accounts" className="link">
              Accounts page
            </Link>{' '}
            to plan an attack.
          </p>
        </Card>
      </div>
    );
  }

  return (
    <div className="stack">
      <GlassHeader
        title="Planer napada"
        subtitle="Mete + napadačka sela + matrica vremena dolaska."
      />

      {flash && (
        <Card>
          <p
            className={
              flash.kind === 'ok'
                ? 'form__msg form__msg--ok'
                : 'form__msg form__msg--error'
            }
          >
            {flash.text}
          </p>
        </Card>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '18rem 1fr',
          gap: '1rem',
          alignItems: 'flex-start',
        }}
      >
        {/* ---- Left: plans list ---- */}
        <Card title="Plans">
          <div className="toolbar" style={{ marginBottom: '0.5rem' }}>
            <button
              type="button"
              className="btn btn--primary btn--sm"
              onClick={() => void onNewPlan()}
              disabled={saving}
            >
              + New plan
            </button>
          </div>
          {loading && <p className="muted">Loading…</p>}
          {!loading && plans.length === 0 && (
            <p className="muted" style={{ margin: 0 }}>
              No plans yet. Click <strong>New plan</strong> to start.
            </p>
          )}
          {plans.length > 0 && (
            <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
              {plans.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(p.id)}
                    className={
                      p.id === selectedId
                        ? 'btn btn--primary btn--sm'
                        : 'btn btn--ghost btn--sm'
                    }
                    style={{
                      width: '100%',
                      justifyContent: 'flex-start',
                      marginBottom: '0.25rem',
                      textAlign: 'left',
                    }}
                  >
                    <span>{p.name}</span>
                    <span
                      className="muted"
                      style={{ marginLeft: 'auto', fontSize: '0.8em' }}
                    >
                      {p.targets.length}×{p.sources.length}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {error && (
            <p className="form__msg form__msg--error" style={{ marginTop: '0.5rem' }}>
              {error}
            </p>
          )}
        </Card>

        {/* ---- Right: editor + matrix ---- */}
        {selectedId == null ? (
          <Card>
            <p className="muted">Pick a plan on the left, or create a new one.</p>
          </Card>
        ) : (
          <div className="stack">
            <PlanEditor
              editor={editor}
              setEditor={setEditor}
              villages={villages}
              onSave={() => void onSave()}
              onDelete={() => void onDelete()}
              saving={saving}
            />
            <MatrixPanel matrix={matrix} loading={matrixLoading} />
          </div>
        )}
      </div>
    </div>
  );
}

// ===========================================================================
//                                Editor
// ===========================================================================

interface EditorState {
  name: string;
  speedFieldsPerHour: string;
  /** datetime-local string in the BROWSER's timezone, or '' for none. */
  landingAtLocal: string;
  targetsText: string;
  selectedSourceIds: Set<number>;
}

function emptyEditor(): EditorState {
  return {
    name: '',
    speedFieldsPerHour: '14',
    landingAtLocal: '',
    targetsText: '',
    selectedSourceIds: new Set(),
  };
}

function planToEditor(plan: AttackPlan): EditorState {
  return {
    name: plan.name,
    speedFieldsPerHour: String(plan.speedFieldsPerHour),
    landingAtLocal: plan.landingAt ? isoToDatetimeLocal(plan.landingAt) : '',
    targetsText: plan.targets
      .map((t) =>
        `${t.x}|${t.y}${t.label && t.label.trim() ? '  ' + t.label : ''}`,
      )
      .join('\n'),
    selectedSourceIds: new Set(plan.sources.map((s) => s.villageId)),
  };
}

function editorToRequest(e: EditorState): AttackPlanRequest {
  return {
    name: e.name.trim(),
    speedFieldsPerHour:
      Number.parseFloat(e.speedFieldsPerHour) > 0
        ? Number.parseFloat(e.speedFieldsPerHour)
        : 14,
    landingAt: e.landingAtLocal ? datetimeLocalToIso(e.landingAtLocal) : null,
    targets: parseTargetsText(e.targetsText),
    sourceVillageIds: Array.from(e.selectedSourceIds),
  };
}

/** Parses one target per line: `X|Y` or `(X|Y)` or `X,Y`, optional trailing label. */
function parseTargetsText(text: string): Array<{
  x: number;
  y: number;
  label: string | null;
}> {
  const out: Array<{ x: number; y: number; label: string | null }> = [];
  const re = /^\s*\(?\s*(-?\d{1,4})\s*[|,]\s*(-?\d{1,4})\s*\)?\s*(.*)$/;
  for (const raw of text.split(/\r?\n/)) {
    const m = raw.match(re);
    if (!m) continue;
    const x = Number.parseInt(m[1], 10);
    const y = Number.parseInt(m[2], 10);
    if (Number.isNaN(x) || Number.isNaN(y)) continue;
    const label = m[3]?.trim();
    out.push({ x, y, label: label ? label : null });
  }
  return out;
}

function isoToDatetimeLocal(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  // Strip seconds + tz; <input type="datetime-local"> wants YYYY-MM-DDTHH:mm.
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  );
}

function datetimeLocalToIso(local: string): string {
  // Treat the input as the BROWSER's local time, convert to UTC ISO.
  const d = new Date(local);
  return d.toISOString();
}

function PlanEditor({
  editor,
  setEditor,
  villages,
  onSave,
  onDelete,
  saving,
}: {
  editor: EditorState;
  setEditor: (next: EditorState) => void;
  villages: Village[];
  onSave: () => void;
  onDelete: () => void;
  saving: boolean;
}) {
  const parsedTargetCount = useMemo(
    () => parseTargetsText(editor.targetsText).length,
    [editor.targetsText],
  );

  const toggleSource = (id: number) => {
    const next = new Set(editor.selectedSourceIds);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setEditor({ ...editor, selectedSourceIds: next });
  };

  const toggleAll = () => {
    if (editor.selectedSourceIds.size === villages.length) {
      setEditor({ ...editor, selectedSourceIds: new Set() });
    } else {
      setEditor({
        ...editor,
        selectedSourceIds: new Set(villages.map((v) => v.id)),
      });
    }
  };

  return (
    <Card title="Plan">
      <div
        className="stack"
        style={{ gap: '1rem' }}
      >
        {/* meta row */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '2fr 1fr 1.5fr',
            gap: '0.75rem',
          }}
        >
          <label>
            <div className="muted" style={{ fontSize: '0.8em' }}>Name</div>
            <input
              type="text"
              value={editor.name}
              onChange={(e) => setEditor({ ...editor, name: e.target.value })}
              style={{ width: '100%' }}
            />
          </label>
          <label>
            <div className="muted" style={{ fontSize: '0.8em' }}>Speed (f/h, x1)</div>
            <input
              type="number"
              min={1}
              max={50}
              step={0.5}
              value={editor.speedFieldsPerHour}
              onChange={(e) =>
                setEditor({ ...editor, speedFieldsPerHour: e.target.value })
              }
              style={{ width: '100%' }}
              title="Base unit speed at server-speed x1. Server multiplier is applied in the matrix."
            />
          </label>
          <label>
            <div className="muted" style={{ fontSize: '0.8em' }}>Landing at (local)</div>
            <input
              type="datetime-local"
              value={editor.landingAtLocal}
              onChange={(e) =>
                setEditor({ ...editor, landingAtLocal: e.target.value })
              }
              style={{ width: '100%' }}
            />
          </label>
        </div>

        {/* targets + sources two-column */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: '1rem',
          }}
        >
          <div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                marginBottom: '0.25rem',
              }}
            >
              <strong>Targets</strong>
              <span className="muted" style={{ fontSize: '0.8em' }}>
                {parsedTargetCount} parsed
              </span>
            </div>
            <textarea
              value={editor.targetsText}
              onChange={(e) =>
                setEditor({ ...editor, targetsText: e.target.value })
              }
              placeholder={'-47|123  JohnDoe — Capital\n(12|-9)\n100,-5  WW1'}
              rows={10}
              style={{
                width: '100%',
                fontFamily: 'monospace',
                fontSize: '0.85em',
              }}
            />
            <div className="muted" style={{ fontSize: '0.75em', marginTop: '0.25rem' }}>
              One per line. Format: <code>X|Y</code> or <code>X,Y</code>, optional label after.
            </div>
          </div>

          <div>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                marginBottom: '0.25rem',
              }}
            >
              <strong>Sources</strong>
              <button
                type="button"
                className="btn btn--ghost btn--sm"
                onClick={toggleAll}
                disabled={villages.length === 0}
                style={{ padding: '0 0.4rem' }}
              >
                {editor.selectedSourceIds.size === villages.length
                  ? 'Clear'
                  : 'All'}
              </button>
            </div>
            <div
              style={{
                maxHeight: '14rem',
                overflowY: 'auto',
                border: '1px solid var(--border, #ccc)',
                borderRadius: '0.25rem',
                padding: '0.25rem 0.5rem',
              }}
            >
              {villages.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>
                  No villages on this account yet.
                </p>
              ) : (
                villages.map((v) => (
                  <label
                    key={v.id}
                    style={{
                      display: 'flex',
                      gap: '0.5rem',
                      alignItems: 'center',
                      padding: '0.15rem 0',
                      cursor: 'pointer',
                    }}
                  >
                    <input
                      type="checkbox"
                      checked={editor.selectedSourceIds.has(v.id)}
                      onChange={() => toggleSource(v.id)}
                    />
                    <span>{v.name}</span>
                    <span
                      className="muted"
                      style={{ fontSize: '0.8em', marginLeft: 'auto' }}
                    >
                      ({v.coordinateX}|{v.coordinateY})
                    </span>
                  </label>
                ))
              )}
            </div>
            <div className="muted" style={{ fontSize: '0.75em', marginTop: '0.25rem' }}>
              {editor.selectedSourceIds.size} selected
            </div>
          </div>
        </div>

        <div className="toolbar">
          <button
            type="button"
            className="btn btn--primary btn--sm"
            onClick={onSave}
            disabled={saving}
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={onDelete}
            disabled={saving}
            style={{ marginLeft: 'auto', color: 'var(--danger, #c0392b)' }}
          >
            Delete plan
          </button>
        </div>
      </div>
    </Card>
  );
}

// ===========================================================================
//                                Matrix
// ===========================================================================

function MatrixPanel({
  matrix,
  loading,
}: {
  matrix: AttackPlanMatrix | null;
  loading: boolean;
}) {
  if (loading) {
    return <Card title="Travel matrix"><p className="muted">Computing…</p></Card>;
  }
  if (!matrix) {
    return (
      <Card title="Travel matrix">
        <p className="muted" style={{ margin: 0 }}>
          Save the plan to compute travel times.
        </p>
      </Card>
    );
  }

  const hasLanding = matrix.landingAt != null;
  const speedLabel =
    matrix.serverSpeedMultiplier === 1
      ? `${matrix.baseSpeedFieldsPerHour} f/h`
      : `${matrix.baseSpeedFieldsPerHour} f/h × ${matrix.serverSpeedMultiplier} = ${matrix.effectiveSpeed.toFixed(1)} f/h`;

  if (matrix.sources.length === 0 || matrix.targets.length === 0) {
    return (
      <Card title="Travel matrix" hint={speedLabel}>
        <p className="muted" style={{ margin: 0 }}>
          {matrix.sources.length === 0
            ? 'No source villages selected.'
            : 'No targets configured.'}
        </p>
      </Card>
    );
  }

  return (
    <Card
      title="Travel matrix"
      hint={`${matrix.sources.length} source${matrix.sources.length === 1 ? '' : 's'} × ${matrix.targets.length} target${matrix.targets.length === 1 ? '' : 's'} · ${speedLabel}`}
    >
      <div style={{ overflowX: 'auto' }}>
        <table className="table" style={{ marginBottom: 0 }}>
          <thead>
            <tr>
              <th style={{ minWidth: '12rem' }}>Source</th>
              {matrix.targets.map((t) => (
                <th key={t.targetId} style={{ minWidth: '8rem' }}>
                  <div>{t.label || `(${t.x}|${t.y})`}</div>
                  {t.label && (
                    <div className="muted" style={{ fontSize: '0.75em' }}>
                      ({t.x}|{t.y})
                    </div>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {matrix.sources.map((src, rowIdx) => (
              <tr key={src.villageId}>
                <td>
                  <div>{src.villageName}</div>
                  <div className="muted" style={{ fontSize: '0.75em' }}>
                    ({src.x}|{src.y})
                  </div>
                </td>
                {matrix.rows[rowIdx].map((cell, colIdx) => (
                  <td key={colIdx}>
                    <CellView cell={cell} showLaunch={hasLanding} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hasLanding && (
        <p className="muted" style={{ fontSize: '0.75em', marginTop: '0.5rem' }}>
          Launch times are absolute (UTC) and assume the speed above. Late means
          the launch instant is already past — drop that source or push the
          landing time back.
        </p>
      )}

      {hasLanding && <SendScheduleSection matrix={matrix} />}
    </Card>
  );
}

/**
 * Per-target send schedule. For each target column the cells are sorted by
 * sendOrder ASC and rendered as a numbered list — the coordinator reads it
 * top-to-bottom and knows exactly who hits the send button first.
 * <p>
 * The Δ next to each row shows the time gap to the NEXT wave's send moment.
 * A small Δ (≤ a few seconds) means the wave order is very tight and the
 * coordinator has to be quick; a big Δ means there's plenty of slack.
 */
function SendScheduleSection({ matrix }: { matrix: AttackPlanMatrix }) {
  const schedules = useMemo(
    () => buildSchedules(matrix),
    [matrix],
  );

  if (schedules.length === 0) return null;

  return (
    <div style={{ marginTop: '1rem' }}>
      <h3 style={{ margin: '0 0 0.5rem 0', fontSize: '0.95rem' }}>
        Send schedule
      </h3>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(20rem, 1fr))',
          gap: '0.75rem',
        }}
      >
        {schedules.map((s) => (
          <div
            key={s.target.targetId}
            style={{
              border: '1px solid var(--border, #ccc)',
              borderRadius: '0.25rem',
              padding: '0.5rem 0.75rem',
              background: 'var(--surface-elevated, transparent)',
            }}
          >
            <div style={{ marginBottom: '0.4rem' }}>
              <strong>
                {s.target.label || `(${s.target.x}|${s.target.y})`}
              </strong>
              {s.target.label && (
                <span className="muted" style={{ marginLeft: '0.4rem', fontSize: '0.8em' }}>
                  ({s.target.x}|{s.target.y})
                </span>
              )}
            </div>
            <ol style={{ paddingLeft: '1.2rem', margin: 0 }}>
              {s.entries.map((e, idx) => {
                const nextEntry = s.entries[idx + 1];
                const deltaSeconds =
                  nextEntry && e.launchAt && nextEntry.launchAt
                    ? Math.max(
                        0,
                        Math.round(
                          (new Date(nextEntry.launchAt).getTime() -
                            new Date(e.launchAt).getTime()) /
                            1000,
                        ),
                      )
                    : null;
                return (
                  <li
                    key={e.source.villageId}
                    style={{
                      marginBottom: '0.25rem',
                      fontFamily: 'monospace',
                      fontSize: '0.85em',
                      color: e.isLate ? 'var(--danger, #c0392b)' : undefined,
                    }}
                  >
                    <span style={{ fontWeight: 600 }}>
                      {e.source.villageName}
                    </span>
                    <span className="muted" style={{ marginLeft: '0.4rem' }}>
                      ({e.source.x}|{e.source.y})
                    </span>
                    {e.launchAt && (
                      <>
                        <span style={{ marginLeft: '0.6rem' }}>
                          {formatClock(e.launchAt)}
                        </span>
                        {deltaSeconds != null && (
                          <span
                            className="muted"
                            style={{ marginLeft: '0.4rem', fontSize: '0.9em' }}
                          >
                            +{formatGap(deltaSeconds)}
                          </span>
                        )}
                      </>
                    )}
                  </li>
                );
              })}
            </ol>
          </div>
        ))}
      </div>
    </div>
  );
}

interface ScheduleEntry {
  source: MatrixSourceRow;
  launchAt: string | null;
  isLate: boolean;
  sendOrder: number;
}

interface Schedule {
  target: MatrixTargetRow;
  entries: ScheduleEntry[];
}

function buildSchedules(matrix: AttackPlanMatrix): Schedule[] {
  const out: Schedule[] = [];
  for (let col = 0; col < matrix.targets.length; col++) {
    const target = matrix.targets[col];
    const entries: ScheduleEntry[] = [];
    for (let row = 0; row < matrix.sources.length; row++) {
      const cell = matrix.rows[row]?.[col];
      if (!cell || cell.sendOrder == null) continue;
      entries.push({
        source: matrix.sources[row],
        launchAt: cell.launchAt,
        isLate: cell.lateBySeconds != null && cell.lateBySeconds >= 0,
        sendOrder: cell.sendOrder,
      });
    }
    entries.sort((a, b) => a.sendOrder - b.sendOrder);
    if (entries.length > 0) out.push({ target, entries });
  }
  return out;
}

/**
 * Compact gap display — under a minute shows raw seconds (the precision the
 * coordinator needs to time clicks), longer gaps roll up to mm:ss / hh:mm:ss.
 */
function formatGap(totalSeconds: number): string {
  if (totalSeconds < 60) return `${totalSeconds}s`;
  return formatDuration(totalSeconds);
}

function CellView({
  cell,
  showLaunch,
}: {
  cell: MatrixCell | null;
  showLaunch: boolean;
}) {
  if (!cell) return <span className="muted">—</span>;
  const travel = formatDuration(cell.travelSeconds);
  const isLate =
    showLaunch && cell.lateBySeconds != null && cell.lateBySeconds >= 0;
  return (
    <div style={{ fontSize: '0.85em' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '0.4rem',
          fontFamily: 'monospace',
        }}
      >
        {cell.sendOrder != null && <WaveBadge order={cell.sendOrder} />}
        <span>{travel}</span>
      </div>
      <div className="muted" style={{ fontSize: '0.85em' }}>
        {cell.distance.toFixed(1)} f
      </div>
      {showLaunch && cell.launchAt && (
        <div
          style={{
            fontFamily: 'monospace',
            color: isLate ? 'var(--danger, #c0392b)' : undefined,
          }}
        >
          {formatClock(cell.launchAt)}
          {isLate && (
            <span className="muted" style={{ marginLeft: '0.25rem' }}>
              late
            </span>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Small inline pill showing the wave number. Drawn as a chip so it's
 * obvious at a glance when scanning the matrix — the user is looking for
 * which sources are wave 1, 2, 3 in the column for the target they care
 * about.
 */
function WaveBadge({ order }: { order: number }) {
  return (
    <span
      title="Wave order — lower number means launch FIRST"
      style={{
        display: 'inline-block',
        minWidth: '1.4rem',
        padding: '0 0.35rem',
        borderRadius: '0.75rem',
        background: order === 1 ? 'var(--primary, #2980b9)' : 'var(--muted, #888)',
        color: '#fff',
        fontSize: '0.75em',
        textAlign: 'center',
        fontFamily: 'system-ui, sans-serif',
        fontWeight: 600,
      }}
    >
      #{order}
    </span>
  );
}

function formatClock(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    month: '2-digit',
    day: '2-digit',
  });
}

function formatDuration(totalSeconds: number): string {
  const s = Math.max(0, Math.round(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(h)}:${pad(m)}:${pad(sec)}`;
}
