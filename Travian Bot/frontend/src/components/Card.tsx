import type { ReactNode } from 'react';

type Props = {
  /**
   * Header content. Accepts a plain string for the common case, or a
   * ReactNode for cards that want pills/badges next to the title
   * (e.g. the build-queue card showing running/pending/failed counts).
   */
  title?: ReactNode;
  hint?: string;
  muted?: boolean;
  children: ReactNode;
};

/**
 * Surface container with optional title + hint header.
 */
export default function Card({ title, hint, muted, children }: Props) {
  return (
    <section className={muted ? 'card card--muted' : 'card'}>
      {title && <h2 className="card__title">{title}</h2>}
      {hint && <p className="card__hint">{hint}</p>}
      {children}
    </section>
  );
}
