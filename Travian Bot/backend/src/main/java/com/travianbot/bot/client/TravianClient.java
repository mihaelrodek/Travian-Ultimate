package com.travianbot.bot.client;

/**
 * Abstraction over how the bot talks to a Travian server.
 * <p>
 * Two implementations are planned:
 * <ul>
 *   <li>{@code BrowserTravianClient} — Playwright-driven Chrome (used for login and JS-heavy flows)</li>
 *   <li>{@code HttpTravianClient} — HttpClient + Jsoup (used for routine scraping once a session exists)</li>
 * </ul>
 * Consumers (page objects, commands, services) depend on this interface and stay transport-agnostic.
 */
public interface TravianClient {

    /**
     * Navigate to a URL and return the resulting page's HTML.
     */
    String get(String url);

    /**
     * HTML of the currently loaded page (no network call).
     */
    String currentHtml();

    /**
     * Perform a Travian login and populate the session with cookies / CSRF tokens.
     * Implementations may open a real browser (Playwright) or replay the login form POST.
     */
    void login();

    /**
     * Tear down browser processes, close HTTP connections, etc.
     */
    void close();

    /**
     * Session state associated with this client (per account).
     */
    TravianSession session();
}
