import type { ReactNode } from 'react';
import Sidebar from './Sidebar';
import FloatingQueueBar from '../features/villages/FloatingQueueBar';

/**
 * Two-column shell — sidebar on the left, page content on the right.
 * <p>
 * Per the GlassHUD design we no longer host a separate topbar above the
 * main content; each page renders its own glass header (see
 * {@link GlassHeader}) and the BackendStatus pill lives in the sidebar
 * footer next to the LOG link.
 * <p>
 * {@link FloatingQueueBar} is pinned to the bottom of the content column
 * so it's visible on every page. The main content has bottom padding
 * (see {@code .layout__main} in index.css) to stop it from hiding behind
 * the bar.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <div className="layout">
      <Sidebar />
      <div className="layout__content">
        <main className="layout__main">{children}</main>
        <FloatingQueueBar />
      </div>
    </div>
  );
}
