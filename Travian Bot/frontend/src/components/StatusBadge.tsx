import type { ReactNode } from 'react';

export type StatusKind = 'up' | 'down' | 'loading' | 'idle';

type Props = {
  kind: StatusKind;
  children: ReactNode;
  title?: string;
};

/**
 * Pill-style status indicator with a colored dot.
 * Styling lives in index.css under {@code .status-badge}.
 */
export default function StatusBadge({ kind, children, title }: Props) {
  return (
    <div className={`status-badge status-badge--${kind}`} title={title}>
      <span className="status-badge__dot" aria-hidden />
      <span>{children}</span>
    </div>
  );
}
