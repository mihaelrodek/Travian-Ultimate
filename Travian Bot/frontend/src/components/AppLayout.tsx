import type { ReactNode } from 'react';
import Sidebar from './Sidebar';
import BackendStatus from '../features/health/BackendStatus';
import FloatingQueueBar from '../features/villages/FloatingQueueBar';

/**
 * Two-column shell — sidebar on the left, page content on the right.
 * The top bar sits above the content column only, so the sidebar feels like a dedicated app shell.
 * <p>
 * {@link FloatingQueueBar} is pinned to the bottom of the content column so it's
 * visible on every page. The main content has bottom padding (see
 * {@code .layout__main} in index.css) to stop it from hiding behind the bar.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="layout">
      <Sidebar />
      <div className="layout__content">
        <header className="layout__topbar">
          <BackendStatus />
        </header>
        <main className="layout__main">{children}</main>
        <FloatingQueueBar />
      </div>
    </div>
  );
}
