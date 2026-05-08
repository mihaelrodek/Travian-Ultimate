import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import Card from '../components/Card';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { notesApi } from '../api/notes';
import type { Note } from '../types/note';

/**
 * Notes for the currently-scoped account. Notes are account-specific —
 * strategy, passwords, reminders — and never make sense across accounts, so
 * there's no "All accounts" view.
 */
export default function NotesListPage() {
  const { accountId, loading: accountsLoading } = useCurrentAccount();

  const [notes, setNotes] = useState<Note[]>([]);
  const [loading, setLoading] = useState<boolean>(accountId != null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (accountId == null) {
      setNotes([]);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    notesApi.list(accountId)
      .then((data) => {
        if (cancelled) return;
        setNotes(data);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'Failed to load notes');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [accountId]);

  return (
    <div className="stack">
      <div className="page__header">
        <div>
          <h1 className="page__title">Notes</h1>
          <p className="page__subtitle">Free-form scratchpad for the active account.</p>
        </div>
        {accountId != null && <Link to="/notes/new" className="btn btn--primary">+ New note</Link>}
      </div>

      {accountId == null && !accountsLoading && (
        <Card>
          <p className="muted">
            Pick an account from the <Link to="/accounts" className="link">Accounts page</Link> to see its notes.
          </p>
        </Card>
      )}

      {accountId != null && (
        <Card>
          {loading && <p className="muted">Loading…</p>}
          {error && <p className="form__msg form__msg--error">{error}</p>}
          {!loading && !error && notes.length === 0 && <p className="muted">No notes yet.</p>}
          {!loading && !error && notes.length > 0 && (
            <table className="table">
              <thead>
                <tr>
                  <th>Title</th>
                  <th style={{ width: '12rem' }}>Updated</th>
                  <th style={{ width: '6rem' }} />
                </tr>
              </thead>
              <tbody>
                {notes.map((n) => (
                  <tr key={n.id}>
                    <td>
                      <Link to={`/notes/${n.id}`} className="link">{n.title}</Link>
                      {n.body && (
                        <div className="muted" style={{ fontSize: '0.85rem', marginTop: '0.2rem' }}>
                          {preview(n.body)}
                        </div>
                      )}
                    </td>
                    <td className="muted">{new Date(n.updatedAt).toLocaleString()}</td>
                    <td><Link to={`/notes/${n.id}`} className="link">Open →</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      )}
    </div>
  );
}

function preview(body: string): string {
  const flat = body.replace(/\s+/g, ' ').trim();
  return flat.length > 120 ? flat.slice(0, 117) + '…' : flat;
}
