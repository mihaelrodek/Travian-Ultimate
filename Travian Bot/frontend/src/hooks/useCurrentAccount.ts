import { useCallback, useEffect, useState } from 'react';
import { useAccounts } from '../features/accounts/AccountsContext';
import type { Account } from '../types/account';

/**
 * "Accounts page is the hub" scoping model — the user picks one account from
 * the Accounts list and everything else (Villages / Notes / Tasks / Farmlists
 * / Statistics) reads that as the current scope.
 *
 * The selection is a single account id persisted in localStorage so it
 * survives reloads but is *not* a backend setting (it's purely a UI choice
 * and switching is frequent enough that server round-trips would be silly).
 *
 * If the selected id no longer resolves to a real account (deleted, list
 * still loading, etc.), {@code account} is {@code null} — each consuming page
 * is responsible for rendering a "pick an account first" fallback.
 */

const LS_CURRENT_ACCOUNT_ID = 'scope.currentAccountId';
const EVENT_NAME = 'scope:current-account-changed';

function readStoredId(): number | null {
  try {
    const raw = localStorage.getItem(LS_CURRENT_ACCOUNT_ID);
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

function writeStoredId(id: number | null): void {
  try {
    if (id == null) localStorage.removeItem(LS_CURRENT_ACCOUNT_ID);
    else localStorage.setItem(LS_CURRENT_ACCOUNT_ID, String(id));
  } catch {
    /* private mode etc. — nothing to do */
  }
}

export interface CurrentAccountState {
  /** The resolved Account record, or null if none selected / not yet loaded. */
  account: Account | null;
  /** The stored id — useful when accounts haven't loaded yet. */
  accountId: number | null;
  /** True while the accounts list is still being fetched. */
  loading: boolean;
  /** Error from loading the accounts list, if any. */
  error: string | null;
  /** Change the scoped account. Pass null to clear. */
  setCurrentAccount: (id: number | null) => void;
}

export function useCurrentAccount(): CurrentAccountState {
  const { accounts, loading, error } = useAccounts();
  const [accountId, setAccountIdState] = useState<number | null>(() => readStoredId());

  // Keep state in sync when other components call setCurrentAccount —
  // a DOM CustomEvent is simpler than wiring a second context.
  useEffect(() => {
    const handler = () => setAccountIdState(readStoredId());
    window.addEventListener(EVENT_NAME, handler);
    window.addEventListener('storage', handler);
    return () => {
      window.removeEventListener(EVENT_NAME, handler);
      window.removeEventListener('storage', handler);
    };
  }, []);

  const setCurrentAccount = useCallback((id: number | null) => {
    writeStoredId(id);
    setAccountIdState(id);
    window.dispatchEvent(new CustomEvent(EVENT_NAME));
  }, []);

  const account = accountId != null ? accounts.find((a) => a.id === accountId) ?? null : null;

  return { account, accountId, loading, error, setCurrentAccount };
}
