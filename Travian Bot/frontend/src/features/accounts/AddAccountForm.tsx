import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { accountsApi } from '../../api/accounts';
import { useAccounts } from './AccountsContext';
import { ApiException } from '../../types/api';
import { accountSlug } from '../../utils/accountSlug';

type Status =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'success'; message: string }
  | { kind: 'error'; message: string };

export default function AddAccountForm() {
  const { refresh } = useAccounts();
  const navigate = useNavigate();
  const [serverUrl, setServerUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [status, setStatus] = useState<Status>({ kind: 'idle' });

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();

    if (!serverUrl.trim() || !username.trim() || !password || !nickname.trim()) {
      setStatus({
        kind: 'error',
        message: 'Server URL, nickname, email and password are all required.',
      });
      return;
    }
    try {
      new URL(serverUrl);
    } catch {
      setStatus({
        kind: 'error',
        message: 'Server URL must be a valid URL (e.g. https://ts1.x1.europe.travian.com).',
      });
      return;
    }

    setStatus({ kind: 'submitting' });
    try {
      // Nickname is required (forms the URL slug); the validation above has
      // already asserted it's non-blank, so we can send a clean trim.
      const account = await accountsApi.create({
        serverUrl: serverUrl.trim(),
        username: username.trim(),
        password,
        nickname: nickname.trim(),
      });
      await refresh();
      setServerUrl('');
      setUsername('');
      setPassword('');
      setNickname('');
      setStatus({ kind: 'success', message: `Saved account ${account.nickname}.` });
      // Navigate directly to the new account's detail page so the user sees
      // proxies / schedule / alerts underneath it without an extra click.
      navigate(`/accounts/${accountSlug(account)}`);
    } catch (err) {
      const message =
        err instanceof ApiException
          ? err.apiError?.details?.join('; ') || err.apiError?.message || err.message
          : err instanceof Error
            ? err.message
            : 'Unknown error';
      setStatus({ kind: 'error', message });
    }
  };

  return (
    <form className="form" onSubmit={onSubmit} noValidate>
      {/* Row 1: server URL alone — it's the widest, user-identifying target
          and the slug derivation anchor, so giving it its own line keeps the
          other rows predictable when the URL is long. */}
      <div className="form__row">
        <label className="form__field">
          <span>Server URL</span>
          <input
            type="url"
            placeholder="https://ts1.x1.europe.travian.com"
            value={serverUrl}
            onChange={(e) => setServerUrl(e.target.value)}
            autoComplete="url"
            spellCheck={false}
          />
        </label>
      </div>

      {/* Row 2: nickname + email. Both are identity-adjacent and comfortable
          sharing a row. Nickname is now required — it becomes part of the
          URL slug so blank is not permitted. */}
      <div className="form__row">
        <label className="form__field">
          <span>Nickname</span>
          <input
            type="text"
            placeholder="Shown in the sidebar; becomes part of the URL"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            spellCheck={false}
            maxLength={100}
          />
        </label>

        <label className="form__field">
          <span>Email address</span>
          <input
            type="email"
            placeholder="you@example.com"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            spellCheck={false}
          />
        </label>
      </div>

      {/* Row 3: password alone — aligns with the edit form layout on the
          account detail page and keeps the sensitive field on its own line
          so muscle memory (tab, type, tab, submit) stays consistent. */}
      <div className="form__row">
        <label className="form__field">
          <span>Password</span>
          <input
            type="password"
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
      </div>

      {/* Compact submit — the Add-account form is short (four inputs), so a
          full-width button feels visually heavy. Small size + center align
          keeps the form breathing and matches the compact edit button on
          the account detail page. */}
      <div className="form__actions form__actions--center">
        <button
          type="submit"
          className="btn btn--primary btn--sm"
          disabled={status.kind === 'submitting'}
        >
          {status.kind === 'submitting' ? 'Saving…' : 'Save account'}
        </button>
      </div>

      {status.kind === 'error' && <p className="form__msg form__msg--error">{status.message}</p>}
      {status.kind === 'success' && <p className="form__msg form__msg--success">{status.message}</p>}
    </form>
  );
}
