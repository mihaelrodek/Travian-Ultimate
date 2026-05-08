import StatusBadge, { type StatusKind } from '../../components/StatusBadge';
import { useBackendHealth } from '../../hooks/useBackendHealth';

/**
 * Polls {@code /api/health} and renders a green/red/pulsing pill in the header.
 */
export default function BackendStatus() {
  const { health, error } = useBackendHealth();

  let kind: StatusKind = 'loading';
  let label = 'Checking backend…';

  if (error) {
    kind = 'down';
    label = 'Backend offline';
  } else if (health) {
    kind = health.status === 'UP' ? 'up' : 'down';
    label = health.status === 'UP' ? 'Backend online' : `Backend: ${health.status}`;
  }

  return (
    <StatusBadge kind={kind} title={error ?? undefined}>
      {label}
    </StatusBadge>
  );
}
