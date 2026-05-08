import { Link, useNavigate } from 'react-router-dom';
import { useAccounts } from './AccountsContext';
import { useCurrentAccount } from '../../hooks/useCurrentAccount';
import { accountSlug } from '../../utils/accountSlug';

/**
 * Compact table of all saved accounts. Each row has two actions:
 *   • "Use this account" — sets it as the current UI scope and navigates into
 *     /villages (where the user typically wants to go next).
 *   • "Open" — shows the account detail page (credentials, per-account
 *     settings, login/logout).
 *
 * The row showing the currently-scoped account gets a subtle highlight so
 * the user always knows where they are.
 */
export default function AccountList() {
  const { accounts, loading, error } = useAccounts();
  const { accountId: scopedId, setCurrentAccount } = useCurrentAccount();
  const navigate = useNavigate();

  if (loading && accounts.length === 0) return <p className="muted">Loading accounts…</p>;
  if (error) return <p className="form__msg form__msg--error">{error}</p>;
  if (accounts.length === 0) return <p className="muted">No accounts yet — add one using the form above.</p>;

  const useAccount = (id: number) => {
    setCurrentAccount(id);
    navigate('/villages');
  };

  return (
    <table className="table">
      <thead>
        <tr>
          <th>Nickname</th>
          <th>Server</th>
          <th>Last login</th>
          <th aria-label="Actions" />
        </tr>
      </thead>
      <tbody>
        {accounts.map((a) => {
          const isScoped = a.id === scopedId;
          // Backend defaults nickname to the username on create, but keep
          // the `|| username` fallback in case a row arrives with a blank
          // alias (e.g. future import path).
          const label = a.nickname || a.username;
          return (
            <tr key={a.id} className={isScoped ? 'row--active' : undefined}>
              <td>
                {label}
                {isScoped && <span className="pill pill--success" style={{ marginLeft: '0.5rem' }}>In use</span>}
              </td>
              <td className="muted">{a.serverUrl}</td>
              <td className="muted">{a.lastLoginAt ? new Date(a.lastLoginAt).toLocaleString() : '—'}</td>
              <td>
                <div style={{ display: 'flex', gap: '0.4rem', justifyContent: 'flex-end' }}>
                  <button
                    type="button"
                    className="btn btn--primary btn--sm"
                    onClick={() => useAccount(a.id)}
                    disabled={isScoped}
                    title={isScoped ? 'Already in use' : 'Scope the app to this account'}
                  >
                    {isScoped ? 'In use' : 'Use this'}
                  </button>
                  <Link to={`/accounts/${accountSlug(a)}`} className="btn btn--ghost btn--sm">
                    Open
                  </Link>
                </div>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
