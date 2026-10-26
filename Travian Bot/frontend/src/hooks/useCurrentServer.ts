import { useCallback, useEffect, useState } from 'react';
import { serversApi } from '../api/servers';
import type { Server } from '../types/server';

/**
 * Server-scoped variant of {@link useCurrentAccount}.
 *
 * The intel / cropper pages search the public map.sql for a *server*, not
 * for a specific account — multiple accounts on the same world share one
 * dataset. The hook stores the picked server id in localStorage so picker
 * state survives reloads but keeps the choice in the UI layer (not on the
 * backend) since switching is frequent and per-tab.
 *
 * Unlike {@link useCurrentAccount}, this hook does **not** auto-pick when
 * nothing is stored — the user has to choose explicitly. We don't want to
 * silently target the wrong world just because the user happened to land on
 * the page; consuming components render an explicit "pick a server" empty
 * state instead.
 */

const LS_CURRENT_SERVER_ID = 'scope.currentServerId';
const EVENT_NAME = 'scope:current-server-changed';

function readStoredId(): number | null {
  try {
    const raw = localStorage.getItem(LS_CURRENT_SERVER_ID);
    if (!raw) return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  } catch {
    return null;
  }
}

function writeStoredId(id: number | null): void {
  try {
    if (id == null) localStorage.removeItem(LS_CURRENT_SERVER_ID);
    else localStorage.setItem(LS_CURRENT_SERVER_ID, String(id));
  } catch {
    /* private mode etc. — nothing to do */
  }
}

export interface CurrentServerState {
  /** The resolved Server record, or null if none selected / not yet loaded. */
  server: Server | null;
  /** The stored id — useful when the server list hasn't loaded yet. */
  serverId: number | null;
  /** True while the servers list is being fetched. */
  loading: boolean;
  /** Error from loading the servers list, if any. */
  error: string | null;
  /** Change the scoped server. Pass null to clear. */
  setCurrentServer: (id: number | null) => void;
  /**
   * Full list — handed out so the picker can render without a second fetch.
   * Refreshes when {@link refresh} is called or when another tab/component
   * dispatches the {@code scope:current-server-changed} event.
   */
  servers: Server[];
  /** Re-fetch the server list (e.g. after create / update / delete). */
  refresh: () => void;
}

export function useCurrentServer(): CurrentServerState {
  const [servers, setServers] = useState<Server[]>([]);
  const [serverId, setServerIdState] = useState<number | null>(() => readStoredId());
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const data = await serversApi.list();
      setServers(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load servers');
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial load.
  useEffect(() => {
    void fetchList();
  }, [fetchList]);

  // Keep state in sync when other components call setCurrentServer (or refresh) —
  // a DOM CustomEvent is simpler than wiring a second context.
  useEffect(() => {
    const handler = () => {
      setServerIdState(readStoredId());
      void fetchList();
    };
    const idOnlyHandler = () => setServerIdState(readStoredId());
    window.addEventListener(EVENT_NAME, handler);
    window.addEventListener('storage', idOnlyHandler);
    return () => {
      window.removeEventListener(EVENT_NAME, handler);
      window.removeEventListener('storage', idOnlyHandler);
    };
  }, [fetchList]);

  const setCurrentServer = useCallback((id: number | null) => {
    writeStoredId(id);
    setServerIdState(id);
    window.dispatchEvent(new CustomEvent(EVENT_NAME));
  }, []);

  const refresh = useCallback(() => {
    void fetchList();
  }, [fetchList]);

  const server = serverId != null ? servers.find((s) => s.id === serverId) ?? null : null;

  return { server, serverId, loading, error, setCurrentServer, servers, refresh };
}
