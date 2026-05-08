import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { generalSettingsApi } from '../../api/settings';
import { setAppLanguage } from '../../i18n';
import type { GeneralSettings, Theme } from '../../types/settings';

interface GeneralSettingsContextValue {
  settings: GeneralSettings | null;
  loading: boolean;
  error: string | null;
  save: (next: GeneralSettings) => Promise<void>;
  refresh: () => Promise<void>;
}

const Ctx = createContext<GeneralSettingsContextValue | null>(null);

/**
 * Loads general settings on mount, keeps them in memory, and applies theme to
 * {@code <html data-theme>} whenever they change. "SYSTEM" clears the attribute so
 * the prefers-color-scheme media query takes over.
 */
export function GeneralSettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<GeneralSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await generalSettingsApi.get();
      setSettings(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load settings');
    } finally {
      setLoading(false);
    }
  }, []);

  const save = useCallback(async (next: GeneralSettings) => {
    const saved = await generalSettingsApi.update(next);
    setSettings(saved);
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    applyTheme(settings?.theme ?? 'SYSTEM');
  }, [settings?.theme]);

  // Sync i18next with the persisted language whenever it changes. Done here
  // (not in the form) so the rest of the app picks up the new language even if
  // it was changed on another device + we reloaded fresh settings.
  useEffect(() => {
    if (settings?.language) {
      setAppLanguage(settings.language);
    }
  }, [settings?.language]);

  const value = useMemo(() => ({ settings, loading, error, save, refresh }), [settings, loading, error, save, refresh]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useGeneralSettings() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useGeneralSettings must be used within GeneralSettingsProvider');
  return ctx;
}

function applyTheme(theme: Theme) {
  const root = document.documentElement;
  if (theme === 'SYSTEM') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', theme.toLowerCase());
  }
}
