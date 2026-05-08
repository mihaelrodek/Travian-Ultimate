import { useEffect, useState } from 'react';
import VillageBuildTab from './VillageBuildTab';
import VillageSettingsTab from './VillageSettingsTab';
import VillageTroopsTab from './VillageTroopsTab';
import type { ReloadAllState } from '../../pages/VillagesListPage';

/**
 * Right-hand detail panel for the Villages master-detail page.
 * <p>
 * Hosts the per-village tabs (Buildings / Troops / Settings) plus the
 * page-level toolbar that wraps them: a collapse handle for the village list
 * on the left, and the "Reload all villages" button on the far right. Putting
 * those controls in the same row as the tabs keeps the page from spending
 * three vertical bands on toolbars.
 * <p>
 * Each tab fetches its own data so switching away and back reloads from
 * source — cheap enough (few KB each) and side-steps stale-state bugs. The
 * selected tab is kept per-village in component state; changing villageId
 * resets to Buildings.
 */
interface Props {
  villageId: number;
  /** True when the parent has hidden the village list — show an "expand" handle instead of "collapse". */
  villagesCollapsed: boolean;
  /** Toggle the village-list collapsed state. Owned by the parent so the preference persists. */
  onToggleVillagesCollapsed: () => void;
  /** Re-scrape every village on the active account. */
  onReloadAll: () => void;
  /** Status of the most recent reload attempt — drives button label + a status pill in the toolbar. */
  reloadState: ReloadAllState;
}

type Tab = 'buildings' | 'troops' | 'settings';

export default function VillageDetailPanel({
  villageId,
  villagesCollapsed,
  onToggleVillagesCollapsed,
  onReloadAll,
  reloadState,
}: Props) {
  const [tab, setTab] = useState<Tab>('buildings');

  // Reset to Buildings when the user picks a different village — otherwise an
  // old "Settings" selection would carry across, feeling sticky in a bad way.
  useEffect(() => {
    setTab('buildings');
  }, [villageId]);

  const reloading = reloadState.kind === 'running';

  return (
    <div className="village-detail">
      <div className="tabs-bar">
        {/*
          Expand handle for the villages list. Only renders when the list is
          collapsed — the matching collapse button lives inside the Villages
          card header (co-located with the list it controls). When the list
          is visible there's nothing to do here, and the tabs sit flush left.
        */}
        {villagesCollapsed && (
          <button
            type="button"
            className="tabs-bar__handle"
            onClick={onToggleVillagesCollapsed}
            aria-label="Show villages list"
            title="Show villages list"
          >
            ›
          </button>
        )}

        <nav className="tabs tabs--inline" role="tablist" aria-label="Village sections">
          <TabButton active={tab === 'buildings'} onClick={() => setTab('buildings')}>Buildings</TabButton>
          <TabButton active={tab === 'troops'} onClick={() => setTab('troops')}>Troops</TabButton>
          <TabButton active={tab === 'settings'} onClick={() => setTab('settings')}>Settings</TabButton>
        </nav>

        {/* Spacer pushes the action group flush to the right edge of the
            toolbar without forcing a fixed width on the tabs. */}
        <div className="tabs-bar__spacer" />

        <div className="tabs-bar__actions">
          {reloadState.kind === 'done' && (
            <span className="muted" style={{ fontSize: '0.8rem' }}>
              Refreshed {reloadState.count} village(s).
            </span>
          )}
          {reloadState.kind === 'error' && (
            <span
              className="form__msg form__msg--error"
              style={{ margin: 0, fontSize: '0.8rem', maxWidth: '24rem' }}
              title={reloadState.message}
            >
              {reloadState.message}
            </span>
          )}
          <button
            type="button"
            className="btn btn--primary btn--sm"
            onClick={onReloadAll}
            disabled={reloading}
            title="Re-scrape every village on this account"
          >
            {reloading ? 'Reloading…' : 'Reload all villages'}
          </button>
        </div>
      </div>

      <div className="tabs__panel">
        {tab === 'buildings' && <VillageBuildTab villageId={villageId} />}
        {tab === 'troops' && <VillageTroopsTab villageId={villageId} />}
        {tab === 'settings' && <VillageSettingsTab villageId={villageId} />}
      </div>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      className={active ? 'tab tab--active' : 'tab'}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
