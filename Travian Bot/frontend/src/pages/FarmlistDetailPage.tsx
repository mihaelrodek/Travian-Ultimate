import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import Card from '../components/Card';
import FarmlistTargetsEditor from '../features/farmlists/FarmlistTargetsEditor';
import FarmlistAnalyticsTab from '../features/farmlists/FarmlistAnalyticsTab';
import { farmlistsApi } from '../api/farmlists';
import { ApiException } from '../types/api';
import type { Farmlist, FarmlistTarget } from '../types/farmlist';

type DetailTab = 'targets' | 'analytics';

type State =
  | { kind: 'loading' }
  | { kind: 'found'; farmlist: Farmlist }
  | { kind: 'missing' }
  | { kind: 'error'; message: string };

type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'saved' } | { kind: 'error'; message: string };

export default function FarmlistDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [state, setState] = useState<State>({ kind: 'loading' });
  const [nameDraft, setNameDraft] = useState('');
  const [descriptionDraft, setDescriptionDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [targetsDraft, setTargetsDraft] = useState<FarmlistTarget[]>([]);
  const [save, setSaveState] = useState<SaveState>({ kind: 'idle' });
  // Tab state lives locally — no need to thread it through the URL since the
  // analytics view is purely a sub-view of the same farmlist.
  const [tab, setTab] = useState<DetailTab>('targets');

  useEffect(() => {
    if (!id) return;
    const fid = Number(id);
    if (Number.isNaN(fid)) {
      setState({ kind: 'missing' });
      return;
    }
    let cancelled = false;
    setState({ kind: 'loading' });
    farmlistsApi.get(fid)
      .then((fl) => {
        if (cancelled) return;
        setState({ kind: 'found', farmlist: fl });
        setNameDraft(fl.name);
        setDescriptionDraft(fl.description ?? '');
        setEnabledDraft(fl.enabled);
        setTargetsDraft(fl.targets);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiException && err.status === 404) {
          setState({ kind: 'missing' });
        } else {
          setState({ kind: 'error', message: err instanceof Error ? err.message : 'Failed to load farmlist' });
        }
      });
    return () => { cancelled = true; };
  }, [id]);

  if (state.kind === 'loading') return <p className="muted">Loading…</p>;
  if (state.kind === 'missing') return <p className="muted">Farmlist not found. <Link to="/farmlists" className="link">← All farmlists</Link></p>;
  if (state.kind === 'error') return <p className="form__msg form__msg--error">{state.message}</p>;

  const fl = state.farmlist;

  const onSave = async () => {
    setSaveState({ kind: 'saving' });
    try {
      const updated = await farmlistsApi.update(fl.id, {
        name: nameDraft.trim(),
        description: descriptionDraft.trim() || null,
        enabled: enabledDraft,
        // Policy fields live on the list page — preserve whatever the server has.
        type: fl.type,
        sendIntervalMinutes: fl.sendIntervalMinutes,
        groupTag: fl.groupTag,
        targets: targetsDraft,
      });
      setState({ kind: 'found', farmlist: updated });
      setTargetsDraft(updated.targets);
      setSaveState({ kind: 'saved' });
    } catch (err) {
      setSaveState({ kind: 'error', message: err instanceof Error ? err.message : 'Save failed' });
    }
  };

  const onDelete = async () => {
    if (!confirm(`Delete farmlist "${fl.name}"?`)) return;
    try {
      await farmlistsApi.delete(fl.id);
      navigate('/farmlists');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Delete failed');
    }
  };

  return (
    <div className="stack">
      <div>
        <Link to="/farmlists" className="link">← All farmlists</Link>
        <h1 className="page__title">{fl.name}</h1>
        <p className="page__subtitle">Account {fl.accountId} · {fl.targets.length} target(s)</p>
      </div>

      {/* Minimal two-tab switcher. Local state only — no router involvement. */}
      <div className="tabs" role="tablist" aria-label="Farmlist sections">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'targets'}
          className={tab === 'targets' ? 'btn btn--primary' : 'btn btn--ghost'}
          onClick={() => setTab('targets')}
        >
          Targets
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'analytics'}
          className={tab === 'analytics' ? 'btn btn--primary' : 'btn btn--ghost'}
          onClick={() => setTab('analytics')}
        >
          Analytics
        </button>
      </div>

      {tab === 'analytics' ? (
        <FarmlistAnalyticsTab accountId={fl.accountId} farmlistId={fl.id} />
      ) : (
      <>
      <Card title="Settings">
        <div className="form">
          <label className="form__field">
            Name
            <input type="text" value={nameDraft} onChange={(e) => setNameDraft(e.target.value)} required />
          </label>
          <label className="form__field">
            Description
            <textarea
              value={descriptionDraft}
              onChange={(e) => setDescriptionDraft(e.target.value)}
              rows={3}
              placeholder="What this list is for…"
              maxLength={500}
            />
          </label>
          <label className="form__check">
            <input type="checkbox" checked={enabledDraft} onChange={(e) => setEnabledDraft(e.target.checked)} />
            <span>Enabled — bot will send raids from this list</span>
          </label>
        </div>
      </Card>

      <Card
        title="Targets"
        hint="Each row is a raid target. Troops is a JSON map of troop slot → count (e.g. {&quot;t1&quot;:50,&quot;t4&quot;:2})."
      >
        <FarmlistTargetsEditor targets={targetsDraft} onChange={setTargetsDraft} />
      </Card>

      <div className="settings-savebar">
        <span className="settings-savebar__msg">
          {save.kind === 'saving' && <span className="muted">Saving…</span>}
          {save.kind === 'saved' && <span className="pill pill--success">Saved</span>}
          {save.kind === 'error' && <span className="form__msg form__msg--error">{save.message}</span>}
        </span>
        <button type="button" className="btn btn--danger" onClick={onDelete}>Delete farmlist</button>
        <button type="button" className="btn btn--ghost" onClick={() => {
          setNameDraft(fl.name);
          setDescriptionDraft(fl.description ?? '');
          setEnabledDraft(fl.enabled);
          setTargetsDraft(fl.targets);
          setSaveState({ kind: 'idle' });
        }}>Reset</button>
        <button type="button" className="btn btn--primary" onClick={onSave} disabled={save.kind === 'saving'}>
          {save.kind === 'saving' ? 'Saving…' : 'Save farmlist'}
        </button>
      </div>
      </>
      )}
    </div>
  );
}
