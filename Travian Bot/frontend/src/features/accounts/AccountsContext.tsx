import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { accountsApi } from '../../api/accounts';
import type { Account } from '../../types/account';

type State = {
  accounts: Account[];
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
};

const AccountsContext = createContext<State | null>(null);

export function AccountsProvider({ children }: { children: ReactNode }) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await accountsApi.list();
      setAccounts(data);
      setError(null);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to load accounts';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const value = useMemo<State>(() => ({ accounts, loading, error, refresh }), [accounts, loading, error, refresh]);

  return <AccountsContext.Provider value={value}>{children}</AccountsContext.Provider>;
}

export function useAccounts(): State {
  const ctx = useContext(AccountsContext);
  if (!ctx) throw new Error('useAccounts must be used inside <AccountsProvider>');
  return ctx;
}
