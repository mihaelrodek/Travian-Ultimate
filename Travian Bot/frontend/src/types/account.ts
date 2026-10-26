export type Account = {
  id: number;
  serverUrl: string;
  username: string;
  /**
   * Display alias chosen when the account was added. Used by the sidebar and
   * the account detail header in place of the raw Travian username. The
   * backend defaults this to {@link #username} when the user leaves the
   * input blank on the create form, so every account always has a printable
   * label (no null branches in the UI).
   */
  nickname: string;
  createdAt: string;
  lastLoginAt: string | null;
  lastLoginStatus: string | null;
  lastLoginMessage: string | null;
  /**
   * Whether the scraper most recently saw an active Travian Plus subscription
   * on this account. Drives the extra build-slot capacity in the queue logic.
   * False when the backend has never scraped the flag yet.
   */
  plusAccountActive: boolean;
  /**
   * Last time the scraper checked Plus status, ISO-8601. Null until the first
   * successful scrape.
   */
  plusAccountCheckedAt: string | null;
  /**
   * Server's UTC offset in whole minutes (e.g. 60 for UTC+01:00). Scraped from
   * the Travian page footer so the UI can render queue finish timestamps in
   * the in-game server clock instead of the user's local clock. Null until
   * the first scrape that finds an offset marker.
   */
  serverUtcOffsetMinutes: number | null;
  /**
   * Last time the scraper updated {@link #serverUtcOffsetMinutes}, ISO-8601.
   */
  serverTimeCheckedAt: string | null;
  /**
   * Live browser-session state: true only when the backend has an open
   * Playwright session AND that session last reached the ingame marker.
   * Cheap truth signal for "is the bot currently hands-on with this
   * account". The sidebar toggles its active-account dot red (active but
   * offline) vs. green (active and live) based on this.
   */
  loggedIn: boolean;
};

/**
 * Response of GET /accounts/{id}/credentials — the plaintext Travian password,
 * fetched lazily (reveal / edit) so it stays out of the routinely-polled
 * {@link Account} payload and off the wire until the user asks for it.
 */
export type AccountCredentials = {
  password: string;
};

export type CreateAccountRequest = {
  serverUrl: string;
  username: string;
  password: string;
  /**
   * Required — the account URL slug is derived from nickname + server, so a
   * blank nickname would yield a malformed route. Enforced by the backend
   * via @NotBlank.
   */
  nickname: string;
};

/**
 * Body for PUT /accounts/{id} — the inline Edit panel on the account detail
 * page. All four fields are required; the form prefills from the current
 * account so renaming-only still sends a complete body.
 */
export type UpdateAccountRequest = {
  serverUrl: string;
  username: string;
  password: string;
  nickname: string;
};

/**
 * One configured proxy row for an account. The API never echoes the proxy
 * password; {@link #passwordSet} tells the UI whether one is stored so the
 * edit form can render a "••••" placeholder.
 */
export type AccountProxy = {
  id: number;
  label: string | null;
  url: string;
  username: string | null;
  passwordSet: boolean;
  active: boolean;
  createdAt: string;
};

export type CreateAccountProxyRequest = {
  label?: string;
  url: string;
  username?: string;
  password?: string;
  active?: boolean;
};

/**
 * Body for PUT /accounts/{accountId}/proxies/{id}. {@code password}
 * semantics: leave the field off to keep the stored value, send empty string
 * to clear, send a non-empty string to replace.
 */
export type UpdateAccountProxyRequest = {
  label?: string;
  url: string;
  username?: string;
  password?: string;
  active?: boolean;
};

export type LoginResult = {
  success: boolean;
  status: 'SUCCESS' | 'FAILED' | 'BUSY' | 'BROWSER_ERROR' | 'ERROR' | string;
  message: string;
  timestamp: string;
};
