import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { notesApi } from '../api/notes';
import { ApiException } from '../types/api';
import type { Note } from '../types/note';

/**
 * Combined new-note + edit-note page. Route /notes/new creates; /notes/:id edits.
 * Kept in one component because the form is identical and the state machine is
 * small enough that splitting would duplicate more than it saves.
 */

type Mode =
  | { kind: 'new' }
  | { kind: 'loading'; id: number }
  | { kind: 'edit'; note: Note }
  | { kind: 'missing' }
  | { kind: 'error'; message: string };

type SaveState = { kind: 'idle' } | { kind: 'saving' } | { kind: 'error'; message: string };

export default function NoteEditorPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { account: scopedAccount, accountId: scopedAccountId } = useCurrentAccount();

  const isNew = id === undefined || id === 'new';

  const [mode, setMode] = useState<Mode>(isNew ? { kind: 'new' } : { kind: 'loading', id: Number(id) });
  const [titleDraft, setTitleDraft] = useState('');
  const [bodyDraft, setBodyDraft] = useState('');
  const [save, setSaveState] = useState<SaveState>({ kind: 'idle' });

  useEffect(() => {
    if (isNew) return;
    const nid = Number(id);
    if (Number.isNaN(nid)) {
      setMode({ kind: 'missing' });
      return;
    }
    let cancelled = false;
    setMode({ kind: 'loading', id: nid });
    notesApi.get(nid)
      .then((n) => {
        if (cancelled) return;
        setMode({ kind: 'edit', note: n });
        setTitleDraft(n.title);
        setBodyDraft(n.body);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiException && err.status === 404) {
          setMode({ kind: 'missing' });
        } else {
          setMode({ kind: 'error', message: err instanceof Error ? err.message : 'Failed to load note' });
        }
      });
    return () => { cancelled = true; };
  }, [id, isNew]);

  const onSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!titleDraft.trim()) {
      setSaveState({ kind: 'error', message: 'Title is required' });
      return;
    }
    setSaveState({ kind: 'saving' });
    try {
      if (mode.kind === 'new') {
        if (scopedAccountId == null) {
          setSaveState({
            kind: 'error',
            message: 'No account selected — pick one from the Accounts page first.',
          });
          return;
        }
        const created = await notesApi.create({
          accountId: scopedAccountId,
          title: titleDraft.trim(),
          body: bodyDraft,
        });
        navigate(`/notes/${created.id}`, { replace: true });
      } else if (mode.kind === 'edit') {
        const updated = await notesApi.update(mode.note.id, {
          title: titleDraft.trim(),
          body: bodyDraft,
        });
        setMode({ kind: 'edit', note: updated });
        setSaveState({ kind: 'idle' });
      }
    } catch (err) {
      setSaveState({ kind: 'error', message: err instanceof Error ? err.message : 'Save failed' });
    }
  };

  const onDelete = async () => {
    if (mode.kind !== 'edit') return;
    if (!confirm(`Delete note "${mode.note.title}"?`)) return;
    try {
      await notesApi.delete(mode.note.id);
      navigate('/notes');
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Delete failed');
    }
  };

  if (mode.kind === 'loading') return <p className="muted">Loading…</p>;
  if (mode.kind === 'missing') return <p className="muted">Note not found. <Link to="/notes" className="link">← All notes</Link></p>;
  if (mode.kind === 'error') return <p className="form__msg form__msg--error">{mode.message}</p>;

  return (
    <div className="stack">
      <div>
        <Link to="/notes" className="link">← All notes</Link>
        <h1 className="page__title">{mode.kind === 'new' ? 'New note' : mode.note.title}</h1>
        {mode.kind === 'edit' && (
          <p className="page__subtitle">
            Updated {new Date(mode.note.updatedAt).toLocaleString()}
          </p>
        )}
      </div>

      <Card>
        <form className="form" onSubmit={onSave}>
          {mode.kind === 'new' && (
            <p className="muted" style={{ margin: 0 }}>
              Account: <strong>{scopedAccount ? scopedAccount.username : 'none selected'}</strong>
              {!scopedAccount && (
                <> — <Link to="/accounts" className="link">pick one first</Link></>
              )}
            </p>
          )}
          <label className="form__field">
            Title
            <input
              type="text"
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              maxLength={200}
              required
            />
          </label>
          <label className="form__field">
            Body
            <textarea
              value={bodyDraft}
              onChange={(e) => setBodyDraft(e.target.value)}
              rows={18}
              className="textarea--mono"
              placeholder="Write whatever you want…"
            />
          </label>
          <div className="settings-savebar">
            <span className="settings-savebar__msg">
              {save.kind === 'saving' && <span className="muted">Saving…</span>}
              {save.kind === 'error' && <span className="form__msg form__msg--error">{save.message}</span>}
            </span>
            {mode.kind === 'edit' && (
              <button type="button" className="btn btn--danger" onClick={onDelete}>Delete</button>
            )}
            <button type="submit" className="btn btn--primary" disabled={save.kind === 'saving'}>
              {mode.kind === 'new' ? 'Create note' : 'Save note'}
            </button>
          </div>
        </form>
      </Card>
    </div>
  );
}
