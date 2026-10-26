// ---------- Enums (match backend string values 1:1) ----------

export const TRIBES = [
  'ROMANS',
  'TEUTONS',
  'GAULS',
  'EGYPTIANS',
  'HUNS',
  'SPARTANS',
  'VIKINGS',
] as const;
export type Tribe = (typeof TRIBES)[number];

export const HERO_ATTRIBUTES = [
  'OFF',
  'FIGHTING_STRENGTH',
  'OFF_BONUS',
  'DEF_BONUS',
  'RESOURCES',
] as const;
export type HeroAttribute = (typeof HERO_ATTRIBUTES)[number];

export const DAYS_OF_WEEK = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;
export type DayOfWeek = (typeof DAYS_OF_WEEK)[number];

export const THEMES = ['LIGHT', 'DARK', 'SYSTEM'] as const;
export type Theme = (typeof THEMES)[number];

/**
 * Supported UI languages. Mirrors the {@code Language} enum in the backend 1:1.
 * Keep this in sync with {@code backend/.../settings/domain/Language.java} and
 * the JSON resource files under {@code src/i18n/} — adding a new language is a
 * three-place change (enum, this union, resource file).
 */
export const LANGUAGES = ['EN', 'HR'] as const;
export type Language = (typeof LANGUAGES)[number];

/** Human-readable labels for the language picker. */
export const LANGUAGE_LABELS: Record<Language, string> = {
  EN: 'English',
  HR: 'Hrvatski',
};

export const ALERT_CHANNELS = ['desktop', 'email', 'webhook'] as const;
export type AlertChannel = (typeof ALERT_CHANNELS)[number];

// ---------- Active windows ----------

export interface ActiveWindow {
  dayOfWeek: DayOfWeek;
  /** Minutes since midnight (0–1440). */
  startMinute: number;
  endMinute: number;
}

// ---------- Account settings ----------

export interface AccountSettings {
  accountId: number;

  /**
   * Auto-detected from the in-game {@code /profile} "Details" section on first
   * successful login. Not user-editable from the UI — it just reflects what
   * the scraper picked up. Stays nullable because brand-new accounts haven't
   * been logged in yet.
   */
  tribe: Tribe | null;

  heroAutoLevel: HeroAttribute;
  heroHealThresholdPct: number;
  heroAutoAdventure: boolean;
  heroMinHealthForAdventure: number;

  alertOnAttack: boolean;
  alertIncludeTroopCounts: boolean;
  alertChannels: string[];

  proxyUrl: string | null;
  proxyUsername: string | null;
  /** True if a password is saved server-side. The actual value is never returned. */
  proxyPasswordSet: boolean;

  /**
   * Launch this account's Chromium without a visible window. Off by default.
   * Takes effect on the next login/restart (headless is fixed at launch).
   */
  headless: boolean;

  activeWindows: ActiveWindow[];
}

/**
 * PUT payload — mirrors {@link AccountSettings} but lets {@code proxyPassword} be:
 *  - null/undefined: leave the server value unchanged
 *  - "": clear it
 *  - any other string: replace it
 */
export type AccountSettingsUpdate = Omit<AccountSettings, 'accountId' | 'proxyPasswordSet'> & {
  proxyPassword?: string | null;
};

// ---------- General settings ----------

export interface GeneralSettings {
  theme: Theme;
  language: Language;
  desktopNotificationsEnabled: boolean;
  /**
   * Play a short Web Audio beep when a critical event (a new incoming attack)
   * is detected while the app is open. Off by default.
   */
  soundAlertsEnabled: boolean;
  defaultCheckIntervalMinutes: number;
  /**
   * Lower bound (ms, inclusive) of the random delay inserted before each browser click/type.
   * Together with {@link maxClickDelayMs} this forms the humanization range.
   */
  minClickDelayMs: number;
  /** Upper bound (ms, inclusive) of the random click delay range. */
  maxClickDelayMs: number;
  /**
   * Hard ceiling on browser actions (navigations/clicks) per minute per
   * account. Where the click-delay range spaces individual actions, this caps
   * the sustained rate so a burst of scheduled work can't fire many requests
   * in a few seconds. 1–600.
   */
  maxActionsPerMinute: number;
  /**
   * Global kill-switch for the per-village auto-scrape scheduler. When
   * false, no village gets scraped automatically regardless of per-village
   * `autoScrapeEnabled`. Manual UI scrapes still work — they bypass the
   * scheduler.
   */
  villageScrapingEnabled: boolean;
  /**
   * Opt-in: when a scheduled task finds the browser session gone, attempt
   * one automatic re-login (cooldown-limited per account) and continue.
   * Off by default — unattended logins can run into captcha loops.
   */
  autoReloginEnabled: boolean;
}
