import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Card from '../components/Card';
import { useAccounts } from '../features/accounts/AccountsContext';
import AccountSettingsPanel from '../features/settings/AccountSettingsPanel';
import { accountsApi } from '../api/accounts';
import { accountSettingsApi } from '../api/settings';
import type { Account } from '../types/account';
import type { Tribe } from '../types/settings';
import { ApiException } from '../types/api';
import { accountSlug, findAccountBySlug } from '../utils/accountSlug';

/**
 * Account detail page. The route is {@code /accounts/{nickname-server}} —
 * the slug is derived from the account's nickname and server hostname in
 * {@link accountSlug}. We resolve the slug via the accounts context on every
 * render so that renaming an account automatically migrates the URL.
 *
 * Layout, top to bottom:
 *   1. Identity card — server URL + nickname + email + password (blurred
 *      behind a reveal toggle) + tribe. An Edit button flips the block into
 *      a form (Row 1: URL, Row 2: Nickname + Email, Row 3: Password).
 *   2. Last login / last status row.
 *   3. Action row: Log in / Restart / Shut down + right-aligned Delete.
 *      Restart and Shut down both require a live session to be enabled.
 *   4. Per-account settings (proxies, schedule, alerts) inline via
 *      {@link AccountSettingsPanel}, each section collapsed by default.
 */

type State =
  | { kind: 'loading' }
  | { kind: 'found'; account: Account }
  | { kind: 'missing' }
  | { kind: 'error'; message: string };

type BusyAction = 'login' | 'shutdown' | 'restart' | 'delete' | null;

export default function AccountDetailPage() {
  const { slug } = useParams<{ slug: string }>();
  const { accounts, refresh: refreshAccounts } = useAccounts();
  const navigate = useNavigate();

  // Resolve slug → account id via the accounts context. The context already
  // has the full list pre-loaded for the sidebar, so there's no extra round
  // trip. Memoized so we don't recompute on every action state change.
  const resolvedId = useMemo(() => {
    if (!slug) return null;
    const hit = findAccountBySlug(accounts, slug);
    return hit ? hit.id : null;
  }, [accounts, slug]);

  const [state, setState] = useState<State>({ kind: 'loading' });
  const [busy, setBusy] = useState<BusyAction>(null);
  const [flash, setFlash] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  // Tribe lives on AccountSettings (scraper auto-detects from /profile).
  // Kept separate so a slow settings call doesn't delay the identity block.
  const [tribe, setTribe] = useState<Tribe | null | undefined>(undefined);

  const [showPassword, setShowPassword] = useState(false);

  // The plaintext password is NOT part of the account payload — it is fetched
  // lazily from /accounts/{id}/credentials on an explicit reveal or edit, then
  // cached here. Reset to null whenever the account (re)loads so a stale
  // password can never leak across accounts.
  const [credPassword, setCredPassword] = useState<string | null>(null);
  const [credLoading, setCredLoading] = useState(false);

  // Inline edit form state. Fields prefill from the loaded account on
  // onBeginEdit; Cancel discards.
  const [editing, setEditing] = useState(false);
  const [editServerUrl, setEditServerUrl] = useState('');
  const [editUsername, setEditUsername] = useState('');
  const [editPassword, setEditPassword] = useState('');
  const [editNickname, setEditNickname] = useState('');
  const [saving, setSaving] = useState(false);

  const loadAccount = (accountId: number, cancelledRef?: { current: boolean }) => {
    setState({ kind: 'loading' });
    accountsApi
      .get(accountId)
      .then((account) => {
        if (cancelledRef?.current) return;
        setState({ kind: 'found', account });
        // Drop any cached credential so the reveal/edit re-fetches for the
        // now-current account.
        setCredPassword(null);
        setShowPassword(false);
      })
      .catch((err: unknown) => {
        if (cancelledRef?.current) return;
        if (err instanceof ApiException && err.status === 404) {
          setState({ kind: 'missing' });
        } else {
          const message = err instanceof Error ? err.message : 'Failed to load account';
          setState({ kind: 'error', message });
        }
      });
  };

  useEffect(() => {
    // The accounts context may still be loading on first render — once it
    // resolves we either find the slug or not. Before the list is ready we
    // stay in 'loading' instead of briefly flashing "Account not found".
    if (accounts.length === 0) return;
    if (resolvedId === null) {
      setState({ kind: 'missing' });
      return;
    }
    const cancelledRef = { current: false };
    loadAccount(resolvedId, cancelledRef);
    return () => {
      cancelledRef.current = true;
    };
  }, [resolvedId, accounts.length]);

  const accountIdForTribe = state.kind === 'found' ? state.account.id : null;
  useEffect(() => {
    if (accountIdForTribe === null) return;
    let cancelled = false;
    accountSettingsApi
      .get(accountIdForTribe)
      .then((s) => { if (!cancelled) setTribe(s.tribe ?? null); })
      .catch(() => { if (!cancelled) setTribe(null); });
    return () => { cancelled = true; };
  }, [accountIdForTribe]);

  const runAction = async (
    action: Exclude<BusyAction, null>,
    call: () => Promise<unknown>,
    okMsg: string,
  ) => {
    if (state.kind !== 'found') return;
    const accountId = state.account.id;
    setBusy(action);
    setFlash(null);
    try {
      await call();
      setFlash({ kind: 'ok', text: okMsg });
      loadAccount(accountId);
      await refreshAccounts();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Action failed';
      setFlash({ kind: 'err', text: message });
    } finally {
      setBusy(null);
    }
  };

  // Fetch-once cache for the plaintext password. Returns the already-loaded
  // value on subsequent calls so reveal + edit share a single round-trip.
  const ensureCredentials = async (accountId: number): Promise<string> => {
    if (credPassword !== null) return credPassword;
    setCredLoading(true);
    try {
      const { password } = await accountsApi.credentials(accountId);
      setCredPassword(password);
      return password;
    } finally {
      setCredLoading(false);
    }
  };

  const onToggleShowPassword = async () => {
    if (state.kind !== 'found') return;
    if (!showPassword) {
      try {
        await ensureCredentials(state.account.id);
      } catch (err) {
        setFlash({
          kind: 'err',
          text: err instanceof Error ? err.message : 'Failed to load password',
        });
        return;
      }
    }
    setShowPassword((v) => !v);
  };

  const onBeginEdit = async () => {
    if (state.kind !== 'found') return;
    const a = state.account;
    setEditServerUrl(a.serverUrl);
    setEditUsername(a.username);
    setEditNickname(a.nickname);
    setFlash(null);
    let pw: string;
    try {
      pw = await ensureCredentials(a.id);
    } catch (err) {
      setFlash({
        kind: 'err',
        text: err instanceof Error ? err.message : 'Failed to load password for editing',
      });
      return;
    }
    setEditPassword(pw);
    setEditing(true);
  };

  const onCancelEdit = () => {
    setEditing(false);
    setFlash(null);
  };

  const onSubmitEdit = async (e: FormEvent) => {
    e.preventDefault();
    if (state.kind !== 'found') return;
    if (
      !editServerUrl.trim()
      || !editUsername.trim()
      || !editPassword
      || !editNickname.trim()
    ) {
      setFlash({
        kind: 'err',
        text: 'Server URL, nickname, email, and password are all required.',
      });
      return;
    }
    try {
      new URL(editServerUrl);
    } catch {
      setFlash({ kind: 'err', text: 'Server URL must be a valid URL.' });
      return;
    }
    const accountId = state.account.id;
    setSaving(true);
    setFlash(null);
    try {
      const updated = await accountsApi.update(accountId, {
        serverUrl: editServerUrl.trim(),
        username: editUsername.trim(),
        password: editPassword,
        nickname: editNickname.trim(),
      });
      await refreshAccounts();
      setEditing(false);
      setFlash({ kind: 'ok', text: 'Account updated.' });
      // Nickname or server URL changes shift the slug — navigate to the new
      // one so the URL stays canonical. If the slug didn't change this is
      // effectively a no-op.
      const newSlug = accountSlug(updated);
      if (newSlug !== slug) {
        navigate(`/accounts/${newSlug}`, { replace: true });
      } else {
        loadAccount(accountId);
      }
    } catch (err) {
      const message =
        err instanceof ApiException
          ? err.apiError?.details?.join('; ') || err.apiError?.message || err.message
          : err instanceof Error
            ? err.message
            : 'Unknown error';
      setFlash({ kind: 'err', text: message });
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async () => {
    if (state.kind !== 'found') return;
    const a = state.account;
    const label = a.nickname || a.username;
    if (!confirm(`Delete account "${label}"? This cannot be undone.`)) return;
    setBusy('delete');
    try {
      await accountsApi.delete(a.id);
      await refreshAccounts();
      navigate('/accounts');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete account';
      setFlash({ kind: 'err', text: message });
      setBusy(null);
    }
  };

  const account = state.kind === 'found' ? state.account : null;
  const loggedIn = account?.loggedIn ?? false;
  // Restart and Shut down both require a live session — Restart is
  // shutdown-then-login, which is wasted effort if there's nothing to shut
  // down. The user asked for the two to gate identically.
  const canCloseSession = loggedIn;

  const displayName = account ? (account.nickname || account.username) : 'Account';

  return (
    <div className="stack stack--wide">
      <div>
        <h1 className="page__title">{displayName}</h1>
      </div>

      {state.kind === 'loading' && <Card><p className="muted">Loading…</p></Card>}
      {state.kind === 'missing' && <Card><p className="muted">Account not found.</p></Card>}
      {state.kind === 'error' && (
        <Card><p className="form__msg form__msg--error">{state.message}</p></Card>
      )}

      {account && (
        <>
          <Card>
            {!editing ? (
              <div className="account-id" role="group" aria-label="Account identity">
                <dl className="kv">
                  <div>
                    <dt>Server URL</dt>
                    <dd>{account.serverUrl}</dd>
                  </div>
                  <div>
                    <dt>Nickname</dt>
                    <dd>{account.nickname}</dd>
                  </div>
                  <div>
                    <dt>Email</dt>
                    <dd>{account.username}</dd>
                  </div>
                  <div>
                    <dt>Password</dt>
                    <dd>
                      <span className="password-field">
                        <input
                          type="text"
                          value={showPassword && credPassword !== null ? credPassword : '••••••••'}
                          readOnly
                          aria-label="Password (read-only)"
                          className="password-field__input"
                        />
                        <button
                          type="button"
                          className="btn btn--ghost btn--sm"
                          onClick={onToggleShowPassword}
                          disabled={credLoading}
                          title={showPassword ? 'Hide password' : 'Show password'}
                        >
                          {credLoading ? '…' : showPassword ? 'Hide' : 'Show'}
                        </button>
                      </span>
                    </dd>
                  </div>
                  <div>
                    <dt>Tribe</dt>
                    <dd>
                      {tribe === undefined ? (
                        <span className="muted">Loading…</span>
                      ) : tribe ? (
                        <span
                          className="chip"
                          title="Auto-detected from the in-game /profile page on first successful login."
                        >
                          {humanizeTribe(tribe)}
                        </span>
                      ) : (
                        <span className="muted">Not fetched yet</span>
                      )}
                    </dd>
                  </div>
                </dl>
                <div className="account-id__actions">
                  <button type="button" className="btn btn--primary btn--sm" onClick={onBeginEdit}>
                    Edit
                  </button>
                </div>
              </div>
            ) : (
              <form className="form" onSubmit={onSubmitEdit} noValidate>
                {/* Row 1: server URL alone. Anchors the slug and tends to be
                    the longest string on the form, so giving it its own
                    line keeps the rows below visually predictable. */}
                <div className="form__row">
                  <label className="form__field">
                    <span>Server URL</span>
                    <input
                      type="url"
                      value={editServerUrl}
                      onChange={(e) => setEditServerUrl(e.target.value)}
                      autoComplete="url"
                      spellCheck={false}
                    />
                  </label>
                </div>
                {/* Row 2: nickname + email. Nickname is required — the slug
                    would otherwise collapse to just the server host. */}
                <div className="form__row">
                  <label className="form__field">
                    <span>Nickname</span>
                    <input
                      type="text"
                      value={editNickname}
                      onChange={(e) => setEditNickname(e.target.value)}
                      spellCheck={false}
                      maxLength={100}
                    />
                  </label>
                  <label className="form__field">
                    <span>Email address</span>
                    <input
                      type="email"
                      value={editUsername}
                      onChange={(e) => setEditUsername(e.target.value)}
                      autoComplete="username"
                      spellCheck={false}
                    />
                  </label>
                </div>
                {/* Row 3: password alone. Mirrors AddAccountForm's layout so
                    muscle memory (tab, tab, type, submit) is consistent. */}
                <div className="form__row">
                  <label className="form__field">
                    <span>Password</span>
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={editPassword}
                      onChange={(e) => setEditPassword(e.target.value)}
                      autoComplete="current-password"
                    />
                  </label>
                </div>
                <div className="form__actions">
                  <button type="submit" className="btn btn--primary btn--sm" disabled={saving}>
                    {saving ? 'Saving…' : 'Save changes'}
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={onCancelEdit}
                    disabled={saving}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="btn btn--ghost btn--sm"
                    onClick={() => setShowPassword((v) => !v)}
                  >
                    {showPassword ? 'Hide password' : 'Show password'}
                  </button>
                </div>
              </form>
            )}

            {flash && (
              <p
                className={
                  flash.kind === 'ok'
                    ? 'form__msg form__msg--ok'
                    : 'form__msg form__msg--error'
                }
                style={{ marginTop: '0.75rem' }}
              >
                {flash.text}
              </p>
            )}

            <hr className="card__sep" />

            {/* Metadata: last login + status. The full message is
                intentionally dropped here — the bot log page is the place
                to dig into failures. */}
            <dl className="kv">
              <div>
                <dt>Last login</dt>
                <dd>
                  {account.lastLoginAt
                    ? new Date(account.lastLoginAt).toLocaleString()
                    : '—'}
                </dd>
              </div>
              <div>
                <dt>Last status</dt>
                <dd>{account.lastLoginStatus ?? '—'}</dd>
              </div>
            </dl>

            <hr className="card__sep" />

            {/* Action row. The leading dot shows live-session state; Log in
                is disabled while logged in; Restart + Shut down both
                require a live session; Delete stays right-aligned via the
                flex spacer. */}
            <div className="account-actions" role="group" aria-label="Account actions">
              <span
                className={`account-toolbar__dot account-toolbar__dot--${loggedIn ? 'online' : 'offline'}`}
                aria-hidden
                title={loggedIn ? 'Session live' : 'Session closed'}
              />
              <button
                type="button"
                className="btn btn--primary"
                onClick={() =>
                  runAction('login', () => accountsApi.login(account.id), 'Login triggered.')
                }
                disabled={busy !== null || loggedIn}
                title={loggedIn ? 'Already logged in' : 'Open a fresh browser and log in'}
              >
                {busy === 'login' ? 'Logging in…' : 'Log in'}
              </button>
              <button
                type="button"
                className="btn btn--primary"
                onClick={() =>
                  runAction('restart', () => accountsApi.restart(account.id), 'Session restarted.')
                }
                disabled={busy !== null || !canCloseSession}
                title={canCloseSession ? 'Close and reopen the browser with a fresh login' : 'No open session'}
              >
                {busy === 'restart' ? 'Restarting…' : 'Restart'}
              </button>
              <button
                type="button"
                className="btn btn--primary"
                onClick={() =>
                  runAction(
                    'shutdown',
                    () => accountsApi.shutdown(account.id),
                    'Browser session closed.',
                  )
                }
                disabled={busy !== null || !canCloseSession}
                title={canCloseSession ? 'Close the browser' : 'No open session'}
              >
                {busy === 'shutdown' ? 'Shutting down…' : 'Shut down'}
              </button>
              <div className="account-actions__spacer" />
              <button
                type="button"
                className="btn btn--danger"
                onClick={onDelete}
                disabled={busy !== null}
              >
                {busy === 'delete' ? 'Deleting…' : 'Delete account'}
              </button>
            </div>
          </Card>

          {/* Per-account settings now live inline on this page: proxies
              (multi-proxy), active schedule, additional options (attack
              alerts). Each section collapsed by default. */}
          <AccountSettingsPanel accountId={account.id} />
        </>
      )}
    </div>
  );
}

function humanizeTribe(tribe: Tribe): string {
  const lower = tribe.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}
