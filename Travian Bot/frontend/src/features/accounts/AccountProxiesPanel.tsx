import { useEffect, useState, type FormEvent } from 'react';
import { proxiesApi } from '../../api/proxies';
import type {
  AccountProxy,
  CreateAccountProxyRequest,
  UpdateAccountProxyRequest,
} from '../../types/account';
import { ApiException } from '../../types/api';

interface Props {
  accountId: number;
}

type Flash = { kind: 'ok' | 'err'; text: string } | null;

/**
 * Multi-proxy editor for an account. Lists every configured proxy with a
 * radio-style "active" selector (only one may be active at a time; the
 * service enforces the invariant) and supports inline edit, delete, and an
 * add-new form at the bottom.
 * <p>
 * Password semantics mirror the backend: leaving the field blank on update
 * means "keep the stored password", typing anything replaces it. The panel
 * uses {@link AccountProxy#passwordSet} to hint that a saved password
 * exists rather than echoing it.
 */
export default function AccountProxiesPanel({ accountId }: Props) {
  const [proxies, setProxies] = useState<AccountProxy[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [flash, setFlash] = useState<Flash>(null);

  // Edit draft keyed by proxy id. Null means "not editing". Having this keyed
  // on id (instead of a single currentlyEditedProxy) lets each row manage its
  // own expand state without re-renders fighting us.
  const [editDrafts, setEditDrafts] = useState<Record<number, EditDraft>>({});

  // Add-new form state.
  const [newLabel, setNewLabel] = useState('');
  const [newUrl, setNewUrl] = useState('');
  const [newUsername, setNewUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [newActive, setNewActive] = useState(false);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoadError(null);
    proxiesApi
      .list(accountId)
      .then((rows) => {
        if (!cancelled) setProxies(rows);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoadError(err instanceof Error ? err.message : 'Failed to load proxies');
      });
    return () => {
      cancelled = true;
    };
  }, [accountId]);

  const reload = async () => {
    try {
      const rows = await proxiesApi.list(accountId);
      setProxies(rows);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : 'Failed to load proxies');
    }
  };

  const onSubmitNew = async (e: FormEvent) => {
    e.preventDefault();
    if (!newUrl.trim()) {
      setFlash({ kind: 'err', text: 'URL is required.' });
      return;
    }
    setAdding(true);
    setFlash(null);
    try {
      const body: CreateAccountProxyRequest = {
        label: newLabel.trim() || undefined,
        url: newUrl.trim(),
        username: newUsername.trim() || undefined,
        password: newPassword || undefined,
        active: newActive,
      };
      await proxiesApi.create(accountId, body);
      await reload();
      setNewLabel('');
      setNewUrl('');
      setNewUsername('');
      setNewPassword('');
      setNewActive(false);
      setFlash({ kind: 'ok', text: 'Proxy added.' });
    } catch (err) {
      setFlash({ kind: 'err', text: describe(err) });
    } finally {
      setAdding(false);
    }
  };

  const beginEdit = (p: AccountProxy) => {
    setEditDrafts((prev) => ({
      ...prev,
      [p.id]: {
        label: p.label ?? '',
        url: p.url,
        username: p.username ?? '',
        // Empty means "keep existing" per update semantics; we never echo
        // the saved value here.
        newPassword: '',
        clearPassword: false,
      },
    }));
    setFlash(null);
  };

  const cancelEdit = (id: number) => {
    setEditDrafts((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
  };

  const patchDraft = (id: number, patch: Partial<EditDraft>) => {
    setEditDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
  };

  const saveEdit = async (p: AccountProxy) => {
    const draft = editDrafts[p.id];
    if (!draft) return;
    if (!draft.url.trim()) {
      setFlash({ kind: 'err', text: 'URL is required.' });
      return;
    }
    // Deliberately omit `active` — Save should not toggle activation. The
    // backend treats null (= field absent) as "don't change", so the active
    // row stays active and vice versa. The checkbox in the row header is
    // the only UI that flips active state.
    const body: UpdateAccountProxyRequest = {
      label: draft.label.trim() || undefined,
      url: draft.url.trim(),
      username: draft.username.trim() || undefined,
      // Empty string clears the stored password, undefined keeps it —
      // JSON.stringify drops undefined keys so the wire body carries no
      // password field when we want "keep".
      password: draft.clearPassword ? '' : draft.newPassword || undefined,
    };
    setFlash(null);
    try {
      await proxiesApi.update(accountId, p.id, body);
      await reload();
      cancelEdit(p.id);
      setFlash({ kind: 'ok', text: 'Proxy updated.' });
    } catch (err) {
      setFlash({ kind: 'err', text: describe(err) });
    }
  };

  const onActivate = async (p: AccountProxy) => {
    if (p.active) return;
    setFlash(null);
    try {
      await proxiesApi.update(accountId, p.id, { url: p.url, active: true });
      await reload();
    } catch (err) {
      setFlash({ kind: 'err', text: describe(err) });
    }
  };

  const onDeactivate = async (p: AccountProxy) => {
    if (!p.active) return;
    setFlash(null);
    try {
      await proxiesApi.update(accountId, p.id, { url: p.url, active: false });
      await reload();
    } catch (err) {
      setFlash({ kind: 'err', text: describe(err) });
    }
  };

  const onDelete = async (p: AccountProxy) => {
    if (!confirm(`Delete proxy "${p.label || p.url}"?`)) return;
    setFlash(null);
    try {
      await proxiesApi.delete(accountId, p.id);
      await reload();
      setFlash({ kind: 'ok', text: 'Proxy deleted.' });
    } catch (err) {
      setFlash({ kind: 'err', text: describe(err) });
    }
  };

  if (loadError) return <p className="form__msg form__msg--error">{loadError}</p>;
  if (proxies === null) return <p className="muted">Loading…</p>;

  return (
    <div className="stack">
      {proxies.length === 0 ? (
        <p className="muted">
          No proxies configured yet. The account will use your machine's
          default network until you add one below.
        </p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th style={{ width: '3rem' }}>Active</th>
              <th>Label</th>
              <th>URL</th>
              <th>Username</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {proxies.map((p) => {
              const draft = editDrafts[p.id];
              const editing = draft !== undefined;
              return (
                <tr key={p.id}>
                  <td>
                    {/* Radio-ish single-active toggle. Clicking a row's
                        checkbox activates it (and deactivates the previous
                        one server-side); un-checking the active row leaves
                        the account with no active proxy. */}
                    <input
                      type="checkbox"
                      checked={p.active}
                      onChange={() => (p.active ? onDeactivate(p) : onActivate(p))}
                      aria-label={p.active ? 'Deactivate proxy' : 'Activate proxy'}
                    />
                  </td>
                  {editing ? (
                    <>
                      <td>
                        <input
                          type="text"
                          value={draft.label}
                          onChange={(e) => patchDraft(p.id, { label: e.target.value })}
                          placeholder="Optional"
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          value={draft.url}
                          onChange={(e) => patchDraft(p.id, { url: e.target.value })}
                          placeholder="http://host:port"
                        />
                      </td>
                      <td>
                        <input
                          type="text"
                          value={draft.username}
                          onChange={(e) => patchDraft(p.id, { username: e.target.value })}
                        />
                      </td>
                      <td>
                        <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap', alignItems: 'center' }}>
                          <input
                            type="password"
                            value={draft.newPassword}
                            onChange={(e) => patchDraft(p.id, { newPassword: e.target.value })}
                            placeholder={p.passwordSet ? '•••• (keep)' : 'Password'}
                            disabled={draft.clearPassword}
                            style={{ width: '9rem' }}
                          />
                          {p.passwordSet && (
                            <label className="form__check" style={{ margin: 0 }}>
                              <input
                                type="checkbox"
                                checked={draft.clearPassword}
                                onChange={(e) => patchDraft(p.id, { clearPassword: e.target.checked })}
                              />
                              <span>Clear</span>
                            </label>
                          )}
                          <button
                            type="button"
                            className="btn btn--primary btn--sm"
                            onClick={() => saveEdit(p)}
                          >
                            Save
                          </button>
                          <button
                            type="button"
                            className="btn btn--ghost btn--sm"
                            onClick={() => cancelEdit(p.id)}
                          >
                            Cancel
                          </button>
                        </div>
                      </td>
                    </>
                  ) : (
                    <>
                      <td>{p.label || <span className="muted">—</span>}</td>
                      <td className="muted">{p.url}</td>
                      <td className="muted">{p.username || <span className="muted">—</span>}</td>
                      <td>
                        <div style={{ display: 'flex', gap: '0.4rem', justifyContent: 'flex-end' }}>
                          <button
                            type="button"
                            className="btn btn--primary btn--sm"
                            onClick={() => beginEdit(p)}
                          >
                            Edit
                          </button>
                          <button
                            type="button"
                            className="btn btn--danger btn--sm"
                            onClick={() => onDelete(p)}
                          >
                            Delete
                          </button>
                        </div>
                      </td>
                    </>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {/* Inline add-new form. Kept at the bottom so the table above always
          represents "what's saved" and the form below is "what you're
          drafting". */}
      <form className="form form--compact" onSubmit={onSubmitNew} noValidate>
        <div className="form__row">
          <label className="form__field">
            <span>Label (optional)</span>
            <input
              type="text"
              value={newLabel}
              onChange={(e) => setNewLabel(e.target.value)}
              placeholder="Residential EU"
              maxLength={100}
            />
          </label>
          <label className="form__field">
            <span>URL</span>
            <input
              type="text"
              value={newUrl}
              onChange={(e) => setNewUrl(e.target.value)}
              placeholder="http://host:port or socks5://host:port"
            />
          </label>
        </div>
        <div className="form__row">
          <label className="form__field">
            <span>Username</span>
            <input
              type="text"
              value={newUsername}
              onChange={(e) => setNewUsername(e.target.value)}
            />
          </label>
          <label className="form__field">
            <span>Password</span>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </label>
        </div>
        <div className="form__actions">
          <label className="form__check" style={{ margin: 0 }}>
            <input
              type="checkbox"
              checked={newActive}
              onChange={(e) => setNewActive(e.target.checked)}
            />
            <span>Activate immediately</span>
          </label>
          <button type="submit" className="btn btn--primary btn--sm" disabled={adding}>
            {adding ? 'Adding…' : 'Add proxy'}
          </button>
        </div>
      </form>

      {flash && (
        <p
          className={
            flash.kind === 'ok' ? 'form__msg form__msg--ok' : 'form__msg form__msg--error'
          }
          style={{ margin: 0 }}
        >
          {flash.text}
        </p>
      )}
    </div>
  );
}

type EditDraft = {
  label: string;
  url: string;
  username: string;
  newPassword: string;
  clearPassword: boolean;
};

function describe(err: unknown): string {
  if (err instanceof ApiException) {
    return err.apiError?.details?.join('; ') || err.apiError?.message || err.message;
  }
  return err instanceof Error ? err.message : 'Unknown error';
}
