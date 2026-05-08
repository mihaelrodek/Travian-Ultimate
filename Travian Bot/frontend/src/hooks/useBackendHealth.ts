import { useEffect, useState } from 'react';
import { healthApi } from '../api/health';
import type { BackendHealth } from '../types/health';

type State = {
  health: BackendHealth | null;
  error: string | null;
};

/**
 * Polls the backend /api/health endpoint every {@link intervalMs} ms.
 * Returns the latest snapshot and any transient error message.
 */
export function useBackendHealth(intervalMs = 10_000): State {
  const [state, setState] = useState<State>({ health: null, error: null });

  useEffect(() => {
    let cancelled = false;

    const tick = async () => {
      try {
        const health = await healthApi.get();
        if (!cancelled) setState({ health, error: null });
      } catch (err) {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : 'Unknown error';
          setState((prev) => ({ health: prev.health, error: message }));
        }
      }
    };

    void tick();
    const id = setInterval(() => void tick(), intervalMs);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [intervalMs]);

  return state;
}
