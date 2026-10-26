import { useEffect, useState, type MouseEvent as ReactMouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAccounts } from '../features/accounts/AccountsContext';
import { useCurrentAccount } from '../hooks/useCurrentAccount';
import { accountSlug } from '../utils/accountSlug';
import { useIncomingAttackWatch } from '../hooks/useIncomingAttackWatch';
import Icon, { type IconName } from './Icon';
import { useBackendHealth } from '../hooks/useBackendHealth';
import { useGeneralSettings } from '../features/settings/GeneralSettingsContext';
import type { Theme } from '../types/settings';

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
const LS_SECTION_DEFENSE_OPEN = 'sidebar.section.defense';
const LS_SECTION_INTEGRATIONS_OPEN = 'sidebar.section.integrations';

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
  const [defenseOpen, setDefenseOpen] = useState<boolean>(() => readBool(LS_SECTION_DEFENSE_OPEN, true));
  const [integrationsOpen, setIntegrationsOpen] = useState<boolean>(() => readBool(LS_SECTION_INTEGRATIONS_OPEN, true));

  // Live count of upcoming (not-yet-landed) incoming attacks for the active
  // account — drives the rose-pink badge on the Defense section header. The
  // hook owns the single incoming poll and also raises desktop notifications
  // for newly-detected attacks (roadmap C3).
  const { incomingCount } = useIncomingAttackWatch(activeAccountId);

  // Resolve the active account record so the switcher block can show the
  // avatar letter + nickname without a second lookup.
  const activeAccount = accounts.find((a) => a.id === activeAccountId) ?? null;

  // Backend health — drives the footer pulse colour (cyan healthy, pink down).
  const { health, error: healthError } = useBackendHealth();
  const backendUp = !healthError && health?.status === 'UP';

  // Theme switcher in the footer — cycles SYSTEM → LIGHT → DARK so the user
  // doesn't have to open Settings just to flip. Persists via the same
  // generalSettingsApi the Settings page uses.
  const { settings: generalSettings, save: saveGeneralSettings } = useGeneralSettings();
  const currentTheme: Theme = generalSettings?.theme ?? 'SYSTEM';
  const cycleTheme = async () => {
    if (!generalSettings) return;
    const next: Theme =
      currentTheme === 'SYSTEM' ? 'LIGHT' : currentTheme === 'LIGHT' ? 'DARK' : 'SYSTEM';
    try {
      await saveGeneralSettings({ ...generalSettings, theme: next });
    } catch (e) {
      // Best-effort — Settings page is the canonical surface for failures.
      console.warn('Theme toggle failed', e);
    }
  };
  const themeIcon: IconName =
    currentTheme === 'LIGHT' ? 'sun' : currentTheme === 'DARK' ? 'moon' : 'monitor';
  const themeLabel =
    currentTheme === 'LIGHT' ? 'Light' : currentTheme === 'DARK' ? 'Dark' : 'System';

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

  useEffect(() => {
    writeBool(LS_SECTION_DEFENSE_OPEN, defenseOpen);
  }, [defenseOpen]);

  useEffect(() => {
    writeBool(LS_SECTION_INTEGRATIONS_OPEN, integrationsOpen);
  }, [integrationsOpen]);

  const sidebarClass = collapsed ? 'sidebar sidebar--collapsed' : 'sidebar';

  return (
    <aside className={sidebarClass}>
      {/* Brand row — gradient shield + TRAVIAN ULTIMATE/HUD stack + a small
          collapse handle pinned to the right edge so the row reads as one
          coherent header instead of three stacked blocks. */}
      <div className="sidebar__brand">
        <span className="gh-brand-logo sidebar__brand-dot" aria-hidden>
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#08101e"
               strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 2l8 4v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6z"/>
          </svg>
        </span>
        {!collapsed && (
          <span className="gh-brand-stack sidebar__brand-label">
            <span className="gh-brand-stack__main">TRAVIAN</span>
            <span className="gh-brand-stack__sub">ULTIMATE / HUD</span>
          </span>
        )}
        <button
          type="button"
          className="gh-brand-collapse"
          onClick={() => setCollapsed((c) => !c)}
          aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
          title={collapsed ? t('nav.expand') : t('nav.collapse')}
        >
          <span aria-hidden>{collapsed ? '»' : '«'}</span>
        </button>
      </div>

      {/* Account switcher card — shows whoever the app is currently scoped to.
          Click to navigate to the accounts page; the per-account sub-list in
          RAČUNI is still the actual switch surface. */}
      {!collapsed && activeAccount && (
        <NavLink
          to={`/accounts/${accountSlug(activeAccount)}`}
          className="gh-account-switcher"
          title={`Active: ${activeAccount.nickname || activeAccount.username}`}
        >
          <span className="gh-account-switcher__avatar" aria-hidden>
            {(activeAccount.nickname || activeAccount.username)
              .charAt(0)
              .toUpperCase()}
          </span>
          <span className="gh-account-switcher__body">
            <span className="gh-account-switcher__name">
              {activeAccount.nickname || activeAccount.username}
            </span>
            <span className="gh-account-switcher__status">
              <span
                className={`gh-account-switcher__dot gh-account-switcher__dot--${
                  activeAccount.loggedIn ? 'online' : 'offline'
                }`}
                aria-hidden
              />
              {activeAccount.loggedIn ? 'ONLINE' : 'OFFLINE'}
              {activeAccount.serverUrl && (
                <span className="gh-account-switcher__server">
                  {' · '}
                  {hostnameOf(activeAccount.serverUrl)}
                </span>
              )}
            </span>
          </span>
          <Icon name="chevron-down" size={15} strokeWidth={2} />
        </NavLink>
      )}

      {/* Collapse button is in the brand row above — no standalone control. */}

      <nav className="sidebar__nav">
        {/* ---------- Top-level: Command Center ---------- */}
        <NavLink to="/" className={navClass} end title={t('nav.commandCenter')}>
          <NavIcon name="grid" />
          {!collapsed && <span className="sidebar__label">{t('nav.commandCenter')}</span>}
        </NavLink>

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
              <NavIcon name="users" />
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

            {/* "Add account" CTA — design wants this as the trailing entry of the
                accounts sublist, distinct blue tint, plus icon. */}
            {!collapsed && (
              <NavLink to="/accounts" className="sidebar__link gh-add-account">
                <NavIcon name="plus" size={14} />
                <span className="sidebar__label">{t('nav.addAccount')}</span>
              </NavLink>
            )}

            <NavLink to="/settings" className={navClass} title={t('nav.settings')}>
              <NavIcon name="sliders" />
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
              <NavIcon name="home" />
              {!collapsed && <span className="sidebar__label">{t('nav.villages')}</span>}
            </NavLink>
            <NavLink to="/troops/counts" className={navClass} title={t('nav.troopCounts')}>
              <NavIcon name="sword" />
              {!collapsed && <span className="sidebar__label">{t('nav.troopCounts')}</span>}
            </NavLink>
            <NavLink to="/notes" className={navClass} title={t('nav.notes')}>
              <NavIcon name="note" />
              {!collapsed && <span className="sidebar__label">{t('nav.notes')}</span>}
            </NavLink>
            <NavLink to="/farmlists" className={navClass} title={t('nav.farmlists')}>
              <NavIcon name="target" />
              {!collapsed && <span className="sidebar__label">{t('nav.farmlists')}</span>}
            </NavLink>
            <NavLink to="/tasks" className={navClass} title={t('nav.tasks')}>
              <NavIcon name="check-square" />
              {!collapsed && <span className="sidebar__label">{t('nav.tasks')}</span>}
            </NavLink>
            <NavLink to="/statistics" className={navClass} title={t('nav.statistics')}>
              <NavIcon name="bar-chart" />
              {!collapsed && <span className="sidebar__label">{t('nav.statistics')}</span>}
            </NavLink>
            <NavLink to="/hero" className={navClass} title={t('nav.hero')}>
              <NavIcon name="star" />
              {!collapsed && <span className="sidebar__label">{t('nav.hero')}</span>}
            </NavLink>
            <NavLink to="/templates" className={navClass} title={t('nav.templates')}>
              <NavIcon name="template" />
              {!collapsed && <span className="sidebar__label">{t('nav.templates')}</span>}
            </NavLink>
            <NavLink to="/ai-analysis" className={navClass} title={t('nav.aiAnalysis')}>
              <NavIcon name="sparkles" />
              {!collapsed && <span className="sidebar__label">{t('nav.aiAnalysis')}</span>}
            </NavLink>
            <NavLink to="/offense/planner" className={navClass} title={t('nav.attackPlanner')}>
              <NavIcon name="crosshair" />
              {!collapsed && <span className="sidebar__label">{t('nav.attackPlanner')}</span>}
            </NavLink>
            <NavLink to="/calculators/travel" className={navClass} title={t('nav.travelCalc')}>
              <NavIcon name="clock" />
              {!collapsed && <span className="sidebar__label">{t('nav.travelCalc')}</span>}
            </NavLink>
            <NavLink to="/map" className={navClass} title={t('nav.map')}>
              <NavIcon name="map" />
              {!collapsed && <span className="sidebar__label">{t('nav.map')}</span>}
            </NavLink>
            <NavLink to="/intel" className={navClass} title={t('nav.intel')}>
              <NavIcon name="search" />
              {!collapsed && <span className="sidebar__label">{t('nav.intel')}</span>}
            </NavLink>
            <NavLink to="/enemy-intel" className={navClass} title={t('nav.enemyIntel')}>
              <NavIcon name="skull" />
              {!collapsed && <span className="sidebar__label">{t('nav.enemyIntel')}</span>}
            </NavLink>
            <NavLink to="/croppers" className={navClass} title={t('nav.croppers')}>
              <NavIcon name="wheat" />
              {!collapsed && <span className="sidebar__label">{t('nav.croppers')}</span>}
            </NavLink>
            <NavLink to="/players" className={navClass} title={t('nav.players')}>
              <NavIcon name="users" />
              {!collapsed && <span className="sidebar__label">{t('nav.players')}</span>}
            </NavLink>
            <NavLink to="/alliances" className={navClass} title={t('nav.alliances')}>
              <NavIcon name="flag" />
              {!collapsed && <span className="sidebar__label">{t('nav.alliances')}</span>}
            </NavLink>
            <NavLink to="/log" className={navClass} title={t('nav.log')}>
              <NavIcon name="list" />
              {!collapsed && <span className="sidebar__label">{t('nav.log')}</span>}
            </NavLink>
          </div>
        )}

        {/* ---------- Section 3: Defense ----------
            Defense gets a rose-pink left border on its sublist + a live count
            badge of upcoming attacks on its header, matching the GlassHUD
            design where the section reads as "the threat surface". */}
        <SectionHeader
          label={t('nav.sections.defense')}
          open={defenseOpen}
          onToggle={() => setDefenseOpen((o) => !o)}
          collapsed={collapsed}
          badge={incomingCount > 0 ? incomingCount : undefined}
          badgeVariant="danger"
        />
        {defenseOpen && (
          <div className="sidebar__section gh-defense-sublist">
            <NavLink to="/incoming" className={navClass} title={t('nav.incoming')}>
              <NavIcon name="shield" />
              {!collapsed && (
                <span className="sidebar__label" style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}>
                  {t('nav.incoming')}
                  {incomingCount > 0 && (
                    <span className="gh-defense-badge" aria-hidden>
                      {incomingCount}
                    </span>
                  )}
                </span>
              )}
            </NavLink>
            <NavLink to="/defense/report-incoming" className={navClass} title={t('nav.reportIncoming')}>
              <NavIcon name="shield" />
              {!collapsed && <span className="sidebar__label">{t('nav.reportIncoming')}</span>}
            </NavLink>
          </div>
        )}

        {/* ---------- Section 4: Integrations ---------- */}
        <SectionHeader
          label={t('nav.sections.integrations')}
          open={integrationsOpen}
          onToggle={() => setIntegrationsOpen((o) => !o)}
          collapsed={collapsed}
        />
        {integrationsOpen && (
          <div className="sidebar__section">
            <NavLink to="/integrations/discord" className={navClass} title={t('nav.discord')}>
              <NavIcon name="message-circle" />
              {!collapsed && <span className="sidebar__label">{t('nav.discord')}</span>}
            </NavLink>
            <NavLink to="/integrations/telegram" className={navClass} title={t('nav.telegram')}>
              <NavIcon name="send" />
              {!collapsed && <span className="sidebar__label">{t('nav.telegram')}</span>}
            </NavLink>
          </div>
        )}
      </nav>

      {/* GlassHUD footer — pulsing dot whose colour mirrors the backend
          health pill (cyan when up, pink when down). Hides when the sidebar
          is collapsed; the brand glow up top already signals "alive". */}
      {!collapsed && (
        <div className="gh-sidebar-footer">
          <span>
            <span
              className={`gh-sidebar-footer__pulse${backendUp ? '' : ' gh-sidebar-footer__pulse--down'}`}
              aria-hidden
            />
            {backendUp ? 'ONLINE' : 'OFFLINE'}
          </span>
          <button
            type="button"
            className="gh-sidebar-footer__theme"
            onClick={() => void cycleTheme()}
            title={`Theme: ${themeLabel} (click to cycle)`}
            aria-label={`Toggle theme — currently ${themeLabel}`}
          >
            <Icon name={themeIcon} size={14} />
          </button>
          <NavLink to="/log" className="gh-sidebar-footer__log" title="Bot log">
            <Icon name="list" size={14} />
            LOG
          </NavLink>
        </div>
      )}
    </aside>
  );
}

/**
 * Clickable section header with a rotating chevron. When the sidebar is
 * collapsed we swap this for a thin divider because there's no room for a
 * label and an always-open section is less confusing than an inert header.
 * <p>
 * Optional {@link badge} renders a tiny gradient pill on the right — used by
 * the Defense section to surface the upcoming-attack count without forcing
 * the user to expand the section.
 */
function SectionHeader({
  label,
  open,
  onToggle,
  collapsed,
  badge,
  badgeVariant,
}: {
  label: string;
  open: boolean;
  onToggle: () => void;
  collapsed: boolean;
  badge?: number | string;
  badgeVariant?: 'danger';
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
      {badge != null && (
        <span
          className={`gh-defense-badge${badgeVariant === 'danger' ? '' : ' gh-defense-badge--neutral'}`}
          style={{ marginLeft: 'auto' }}
          aria-hidden
        >
          {badge}
        </span>
      )}
    </button>
  );
}

function navClass({ isActive }: { isActive: boolean }): string {
  return isActive ? 'sidebar__link sidebar__link--active' : 'sidebar__link';
}

/**
 * Wrapper that drops the inline SVG into a span with the existing
 * .sidebar__icon class so theme rules keep targeting it consistently.
 */
function NavIcon({
  name,
  size,
  strokeWidth,
}: {
  name: IconName;
  size?: number;
  strokeWidth?: number;
}) {
  return (
    <span className="sidebar__icon" aria-hidden>
      <Icon name={name} size={size} strokeWidth={strokeWidth} />
    </span>
  );
}

/**
 * Pull a friendly host label out of a full server URL — keeps the account
 * switcher's status row from showing the full https:// path.
 */
function hostnameOf(serverUrl: string): string {
  try {
    return new URL(serverUrl).hostname.replace(/^www\./, '');
  } catch {
    return serverUrl;
  }
}
