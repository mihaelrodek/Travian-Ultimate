import { useEffect, useState, type ReactNode } from 'react';

/**
 * GlassHUD page header — title in Chakra Petch + soft sub-line on the left,
 * pills on the right. Default right slot renders a live clock pill and a
 * placeholder QUEUE pill so every page has a consistent header without
 * having to wire its own tickers.
 *
 * Pages can override the right slot for page-specific actions (e.g. the
 * Incoming-attacks page wants a "LIVE" badge instead of QUEUE).
 */
export default function GlassHeader({
  title,
  subtitle,
  right,
}: {
  title: string;
  subtitle?: string;
  right?: ReactNode;
}) {
  const [now, setNow] = useState<number>(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const clock = formatClock(now);

  return (
    <header className="gh-header">
      <div>
        <div className="gh-header__title">{title}</div>
        {subtitle && <div className="gh-header__sub">{subtitle}</div>}
      </div>
      <div className="gh-header__right">
        {right ?? (
          <>
            <span className="gh-pill gh-pill--clock">{clock}</span>
            <span className="gh-pill">QUEUE · 0</span>
          </>
        )}
      </div>
    </header>
  );
}

function formatClock(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
