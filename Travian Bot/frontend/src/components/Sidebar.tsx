import { useEffect, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAccounts } from '../features/accounts/AccountsContext';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { accountSlug } from '../utils/accountSlug';

/**
 * Left navigation.
 *
 * Two collapsible sections:
 *  1. "Accounts" — account list + per-account links + global Settings.
 *  2. "Game" — Villages, Notes, Farmlists, Tasks, Statistics, Hero.
 *
 * The whole sidebar can shrink to icons-only via a collapse toggle. UI state
 * (sidebar collapsed + per-section open) is persisted to localStorage — the
 * narrow "ephemeral UI state" exception to backend-persisted settings.
 */

const LS_SIDEBAR_COLLAPSED = 'sidebar.collapsed';
const LS_SECTION_ACCOUNTS_OPEN = 'sidebar.section.accounts';
const LS_SECTION_GAME_OPEN = 'sidebar.section.game';

function readBool(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key);
    if (v === null) return fallback;
    return v === 'true';
  } catch {
    return fallback;
  }
}

function writeBool(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    /* ignore — private mode etc. */
  }
}

export default function Sidebar() {
  const { t } = useTranslation();
  const { accounts, loading, error } = useAccounts();
  const { accountId: activeAccountId, setCurrentAccount } = useCurrentAccount();
  const navigate = useNavigate();

  /**
   * Handle a click on an account row. The product shape:
   *   - If this account is already the active one, just navigate to its page
   *     (the user is asking to view it, not to switch).
   *   - Otherwise prompt the user; on confirm, flip the app-wide active
   *     account and navigate to its detail page so every scoped page
   *     ({@link /villages}, {@link /notes}, {@link /hero}, ...) re-reads its
   *     data from the new scope. On cancel, do nothing — the user stays on
   *     the previously active account and the URL is untouched.
   *
   * The first-time selection path (no active account yet) skips the confirm
   * dialog — there's nothing to confirm away from.
   */
  const onAccountClick = (
    e: ReactMouseEvent,
    targetId: number,
    displayName: string,
    targetSlug: string,
  ) => {
    if (targetId === activeAccountId) return; // let NavLink handle it
    e.preventDefault();
    if (activeAccountId == null) {
      setCurrentAccount(targetId);
      navigate(`/accounts/${targetSlug}`);
      return;
    }
    const ok = window.confirm(
      `Change active account to "${displayName}"?\n\n`
        + `Villages, hero, queues and settings for the current account will be replaced `
        + `with the ones for "${displayName}".`,
    );
    if (!ok) return;
    setCurrentAccount(targetId);
    navigate(`/accounts/${targetSlug}`);
  };

  const [collapsed, setCollapsed] = useState<boolean>(() => readBool(LS_SIDEBAR_COLLAPSED, false));
  const [accountsOpen, setAccountsOpen] = useState<boolean>(() => readBool(LS_SECTION_ACCOUNTS_OPEN, true));
  const [gameOpen, setGameOpen] = useState<boolean>(() => readBool(LS_SECTION_GAME_OPEN, true));

  // Expose sidebar width to the layout grid via a CSS variable on :root.
  useEffect(() => {
    writeBool(LS_SIDEBAR_COLLAPSED, collapsed);
    document.documentElement.style.setProperty('--sidebar-width', collapsed ? '64px' : '240px');
  }, [collapsed]);

  useEffect(() => {
    writeBool(LS_SECTION_ACCOUNTS_OPEN, accountsOpen);
  }, [accountsOpen]);

  useEffect(() => {
    writeBool(LS_SECTION_GAME_OPEN, gameOpen);
  }, [gameOpen]);

  const sidebarClass = collapsed ? 'sidebar sidebar--collapsed' : 'sidebar';

  return (
    <aside className={sidebarClass}>
      <div className="sidebar__brand">
        <span className="sidebar__brand-dot" aria-hidden />
        {!collapsed && <span className="sidebar__brand-label">{t('brand.name')}</span>}
      </div>

      <button
        type="button"
        className="sidebar__collapse-btn"
        onClick={() => setCollapsed((c) => !c)}
        aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
        title={collapsed ? t('nav.expand') : t('nav.collapse')}
      >
        <span aria-hidden>{collapsed ? '»' : '«'}</span>
      </button>

      <nav className="sidebar__nav">
        {/* ---------- Section 1: Accounts ---------- */}
        <SectionHeader
          label={t('nav.sections.accounts')}
          open={accountsOpen}
          onToggle={() => setAccountsOpen((o) => !o)}
          collapsed={collapsed}
        />
        {accountsOpen && (
          <div className="sidebar__section">
            <NavLink to="/accounts" className={navClass} end title={t('nav.accounts')}>
              <span className="sidebar__icon" aria-hidden>◎</span>
              {!collapsed && <span className="sidebar__label">{t('nav.accounts')}</span>}
            </NavLink>

            {!collapsed && (
              <ul className="sidebar__sublist">
                {loading && <li className="sidebar__subhint">{t('common.loading')}</li>}
                {error && <li className="sidebar__subhint sidebar__subhint--error">{error}</li>}
                {!loading && !error && accounts.length === 0 && (
                  <li className="sidebar__subhint">{t('nav.noAccounts')}</li>
                )}
                {accounts.map((a) => {
                  // Prefer nickname for display; server guarantees it's set
                  // (defaulted to username on create) but we keep the
                  // `|| a.username` fallback for forward-compat with any
                  // cached rows that might arrive without it.
                  const label = a.nickname || a.username;
                  const isActive = a.id === activeAccountId;
                  // We roll our own className instead of relying on NavLink's
                  // isActive (which only considers URL matching) because the
                  // "active account" concept is app-level, not route-level.
                  // The URL may point to /villages while the user's active
                  // account is Alice — we still want Alice's row highlighted
                  // in the sidebar.
                  //
                  // Dot colour encodes two orthogonal things:
                  //   - active + loggedIn  → green  (bot is hands-on)
                  //   - active + !loggedIn → red    (active but offline)
                  //   - not active         → grey   (everything else)
                  // Only the active row flips colour — inactive rows always
                  // read grey so the user isn't confronted with a wall of
                  // red dots for accounts the bot hasn't been asked to run.
                  const isOffline = isActive && !a.loggedIn;
                  const dotVariant = isActive
                    ? a.loggedIn
                      ? 'active'
                      : 'offline'
                    : 'inactive';
                  const rowVariant = isActive
                    ? a.loggedIn
                      ? 'sidebar__account--active'
                      : 'sidebar__account--offline'
                    : 'sidebar__account--inactive';
                  const className = `sidebar__link sidebar__account ${rowVariant}`;
                  const title = isActive
                    ? a.loggedIn
                      ? `${label} — active and logged in`
                      : `${label} — active but not logged in`
                    : `Switch to ${label}`;
                  const slug = accountSlug(a);
                  return (
                    <li key={a.id}>
                      <NavLink
                        to={`/accounts/${slug}`}
                        className={className}
                        onClick={(e) => onAccountClick(e, a.id, label, slug)}
                        title={title}
                      >
                        <span
                          className={`sidebar__account-dot sidebar__account-dot--${dotVariant}`}
                          aria-hidden
                        />
                        <span className="sidebar__subtext">{label}</span>
                        {isOffline && (
                          <span className="sidebar__account-badge" aria-hidden>!</span>
                        )}
                      </NavLink>
                    </li>
                  );
                })}
              </ul>
            )}

            <NavLink to="/settings" className={navClass} title={t('nav.settings')}>
              <span className="sidebar__icon" aria-hidden>⚙</span>
              {!collapsed && <span className="sidebar__label">{t('nav.settings')}</span>}
            </NavLink>
          </div>
        )}

        {/* ---------- Section 2: Game ---------- */}
        <SectionHeader
          label={t('nav.sections.game')}
          open={gameOpen}
          onToggle={() => setGameOpen((o) => !o)}
          collapsed={collapsed}
        />
        {gameOpen && (
          <div className="sidebar__section">
            <NavLink to="/villages" className={navClass} title={t('nav.villages')}>
              <span className="sidebar__icon" aria-hidden>⌂</span>
              {!collapsed && <span className="sidebar__label">{t('nav.villages')}</span>}
            </NavLink>
            <NavLink to="/notes" className={navClass} title={t('nav.notes')}>
              <span className="sidebar__icon" aria-hidden>✎</span>
              {!collapsed && <span className="sidebar__label">{t('nav.notes')}</span>}
            </NavLink>
            <NavLink to="/farmlists" className={navClass} title={t('nav.farmlists')}>
              <span className="sidebar__icon" aria-hidden>⚔</span>
              {!collapsed && <span className="sidebar__label">{t('nav.farmlists')}</span>}
            </NavLink>
            <NavLink to="/tasks" className={navClass} title={t('nav.tasks')}>
              <span className="sidebar__icon" aria-hidden>☑</span>
              {!collapsed && <span className="sidebar__label">{t('nav.tasks')}</span>}
            </NavLink>
            <NavLink to="/statistics" className={navClass} title={t('nav.statistics')}>
              <span className="sidebar__icon" aria-hidden>▤</span>
              {!collapsed && <span className="sidebar__label">{t('nav.statistics')}</span>}
            </NavLink>
            <NavLink to="/hero" className={navClass} title={t('nav.hero')}>
              <span className="sidebar__icon" aria-hidden>♞</span>
              {!collapsed && <span className="sidebar__label">{t('nav.hero')}</span>}
            </NavLink>
            <NavLink to="/templates" className={navClass} title={t('nav.templates')}>
              <span className="sidebar__icon" aria-hidden>▣</span>
              {!collapsed && <span className="sidebar__label">{t('nav.templates')}</span>}
            </NavLink>
            <NavLink to="/ai-analysis" className={navClass} title={t('nav.aiAnalysis')}>
              <span className="sidebar__icon" aria-hidden>✦</span>
              {!collapsed && <span className="sidebar__label">{t('nav.aiAnalysis')}</span>}
            </NavLink>
            <NavLink to="/log" className={navClass} title={t('nav.log')}>
              <span className="sidebar__icon" aria-hidden>☰</span>
              {!collapsed && <span className="sidebar__label">{t('nav.log')}</span>}
            </NavLink>
          </div>
        )}
      </nav>
    </aside>
  );
}

/**
 * Clickable section header with a rotating chevron. When the sidebar is
 * collapsed we swap this for a thin divider because there's no room for a
 * label and an always-open section is less confusing than an inert header.
 */
function SectionHeader({
  label,
  open,
  onToggle,
  collapsed,
}: {
  label: string;
  open: boolean;
  onToggle: () => void;
  collapsed: boolean;
}) {
  if (collapsed) {
    return <div className="sidebar__section-divider" aria-hidden />;
  }
  return (
    <button
      type="button"
      className="sidebar__section-header"
      onClick={onToggle}
      aria-expanded={open}
    >
      <span className={`sidebar__section-chevron${open ? ' sidebar__section-chevron--open' : ''}`} aria-hidden>
        ▸
      </span>
      <span className="sidebar__section-label">{label}</span>
    </button>
  );
}

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'sidebar__link sidebar__link--active' : 'sidebar__link';
}
