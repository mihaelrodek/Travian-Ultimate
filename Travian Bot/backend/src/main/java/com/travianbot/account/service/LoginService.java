package com.travianbot.account.service;

import com.microsoft.playwright.PlaywrightException;
import com.travianbot.account.api.dto.LoginResultResponse;
import com.travianbot.account.domain.Account;
import com.travianbot.bot.client.TravianSession;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.client.browser.CaptchaGate;
import com.travianbot.bot.client.browser.LoginFailedException;
import com.travianbot.bot.scraper.FarmlistScrapingService;
import com.travianbot.bot.scraper.ProfileScraper;
import com.travianbot.bot.scraper.ScrapingService;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.notification.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates a login attempt for a given account:
 * look up credentials → get-or-create the per-account browser session → run login → record outcome.
 * <p>
 * Unlike the earlier try-with-resources version, this service does NOT close the browser after
 * a successful login — ownership of the long-lived client belongs to {@link BrowserSessionManager}.
 * That lets a follow-up scrape reuse the authenticated page without re-opening Chromium.
 * On failure, we release the client so a retry starts from a clean slate.
 * <p>
 * A small in-memory guard prevents concurrent login attempts for the same account id from stacking.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AccountService accountService;
    private final BrowserSessionManager sessionManager;
    /**
     * Injected via {@link ObjectProvider} to break the {@code login → scrape → account}
     * dependency cycle that would otherwise force a refactor of ScrapingService.
     */
    private final ObjectProvider<ScrapingService> scrapingServiceProvider;
    /**
     * Same cycle-breaker story as {@code scrapingServiceProvider} — the farmlist
     * scraper depends on session + account lookup, and pulling it in eagerly
     * would re-create the dependency cycle LoginService is already side-stepping.
     */
    private final ObjectProvider<FarmlistScrapingService> farmlistScrapingServiceProvider;
    /**
     * Same cycle-breaker story — {@link ProfileScraper} depends on
     * {@code AccountSettingsService}, and pulling it in eagerly would tangle the
     * login → settings → account graph. Lazy lookup keeps the wiring clean.
     */
    private final ObjectProvider<ProfileScraper> profileScraperProvider;
    private final BotEventService botEventService;
    private final NotificationDispatcher notifiers;
    private final CaptchaGate captchaGate;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public LoginService(
            AccountService accountService,
            BrowserSessionManager sessionManager,
            ObjectProvider<ScrapingService> scrapingServiceProvider,
            ObjectProvider<FarmlistScrapingService> farmlistScrapingServiceProvider,
            ObjectProvider<ProfileScraper> profileScraperProvider,
            BotEventService botEventService,
            NotificationDispatcher notifiers,
            CaptchaGate captchaGate
    ) {
        this.accountService = accountService;
        this.sessionManager = sessionManager;
        this.scrapingServiceProvider = scrapingServiceProvider;
        this.farmlistScrapingServiceProvider = farmlistScrapingServiceProvider;
        this.profileScraperProvider = profileScraperProvider;
        this.botEventService = botEventService;
        this.notifiers = notifiers;
        this.captchaGate = captchaGate;
    }

    public LoginResultResponse login(Long accountId) {
        if (!inFlight.add(accountId)) {
            return LoginResultResponse.failure("BUSY", "Login already in progress for this account.");
        }

        try {
            Account account = accountService.get(accountId);

            // Always start from a fresh client on explicit login — the user may have rotated
            // credentials or the previous browser may be in an unknown state.
            sessionManager.release(accountId);

            BrowserTravianClient client = sessionManager.getOrCreate(accountId, () -> new TravianSession(
                    account.getServerUrl(),
                    account.getUsername(),
                    account.getPassword()
            ));

            try {
                client.login();
            } catch (RuntimeException e) {
                // Release so a retry gets a clean Chromium.
                sessionManager.release(accountId);
                throw e;
            }

            String msg = "Logged in as " + account.getUsername() + " on " + account.getServerUrl();
            accountService.recordLoginAttempt(accountId, "SUCCESS", msg);
            botEventService.record(accountId, null, "LOGIN", BotEventOutcome.OK, msg);
            log.info("Login success for account {} ({})", accountId, account.getUsername());

            // A successful login proves any earlier captcha is solved — reopen
            // the gate so schedulers and auto-relogin resume.
            captchaGate.clear(accountId);

            // Best-effort: scrape the current village immediately so the UI has something to show.
            // Failures here must NOT overwrite the SUCCESS status — the user is logged in either way.
            tryAutoScrape(accountId);

            return LoginResultResponse.success(msg);

        } catch (LoginFailedException e) {
            log.warn("Login failed for account {}: {}", accountId, e.getMessage());
            accountService.recordLoginAttempt(accountId, "FAILED", e.getMessage());
            botEventService.record(accountId, null, "LOGIN", BotEventOutcome.ERROR,
                    "Login failed: " + e.getMessage());
            notifyLoginFailure(accountId, e.getMessage());
            return LoginResultResponse.failure("FAILED", e.getMessage());
        } catch (PlaywrightException e) {
            String msg = e.getMessage() == null ? "Unknown browser error" : e.getMessage();
            log.error("Playwright error for account {}: {}", accountId, msg);
            accountService.recordLoginAttempt(accountId, "BROWSER_ERROR", msg);
            botEventService.record(accountId, null, "LOGIN", BotEventOutcome.ERROR,
                    "Browser error: " + msg);
            // Treat a Playwright crash as session-lost — the browser is gone
            // either way, so the user needs the same "your bot stopped"
            // signal as a soft logout. Captcha never surfaces here, so we
            // don't bother with the heuristic split.
            safeNotifySessionLost(accountId, "Browser error: " + msg);
            return LoginResultResponse.failure("BROWSER_ERROR", msg);
        } catch (Exception e) {
            log.error("Unexpected error for account {}", accountId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            accountService.recordLoginAttempt(accountId, "ERROR", msg);
            botEventService.record(accountId, null, "LOGIN", BotEventOutcome.ERROR,
                    "Unexpected login error: " + msg);
            safeNotifySessionLost(accountId, "Unexpected: " + msg);
            return LoginResultResponse.failure("ERROR", msg);
        } finally {
            inFlight.remove(accountId);
        }
    }

    /**
     * Pick the right notification kind from a {@link LoginFailedException}
     * message — if the text mentions "captcha", surface a captcha alert
     * (orange / "needs human attention"); otherwise treat as a generic
     * session-lost. Best-effort — exceptions here must not affect the login
     * result the caller already chose.
     */
    private void notifyLoginFailure(Long accountId, String message) {
        String text = message == null ? "" : message;
        boolean isCaptcha = text.toLowerCase(java.util.Locale.ROOT).contains("captcha");
        try {
            if (isCaptcha) {
                notifiers.notifyCaptcha(accountId, text);
            } else {
                notifiers.notifySessionLost(accountId, text);
            }
        } catch (RuntimeException e) {
            log.debug("Notify after login failure failed for account {}: {}",
                    accountId, e.toString());
        }
    }

    private void safeNotifySessionLost(Long accountId, String reason) {
        try {
            notifiers.notifySessionLost(accountId, reason);
        } catch (RuntimeException e) {
            log.debug("Notify (session-lost) failed for account {}: {}",
                    accountId, e.toString());
        }
    }

    /**
     * Shuts down the local Playwright session for this account WITHOUT
     * performing an ingame logout click. The Travian server still considers
     * the session live until the cookie naturally expires — use this when
     * you want to stop the bot cheaply, not when you want to end the game
     * session cleanly.
     */
    public LoginResultResponse shutdown(Long accountId) {
        sessionManager.release(accountId);
        accountService.recordLoginAttempt(accountId, "LOGGED_OUT", "Browser session closed");
        botEventService.record(accountId, null, "SHUTDOWN", BotEventOutcome.OK, "Browser session closed (no ingame logout).");
        return LoginResultResponse.success("Browser session closed.");
    }

    /**
     * Clicks the ingame Logout link so Travian advances its own {@code last
     * login} timestamp and ends the server-side session, then closes the
     * browser. Requires an existing open session — if none is open, this is a
     * no-op that still records a LOGGED_OUT status so callers can treat it
     * idempotently.
     */
    public LoginResultResponse inGameLogout(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null) {
            // Nothing to click — act idempotently, don't treat as an error.
            accountService.recordLoginAttempt(accountId, "LOGGED_OUT", "No open browser session; nothing to do");
            botEventService.record(accountId, null, "LOGOUT", BotEventOutcome.OK, "No open browser session.");
            return LoginResultResponse.success("No open browser session.");
        }

        String msg;
        try {
            Account account = accountService.get(accountId);
            boolean clicked = client.clickLogout(account.getServerUrl());
            msg = clicked
                    ? "Clicked ingame logout link."
                    : "Ingame logout link not found — closing session anyway.";
        } catch (Exception e) {
            log.warn("Ingame logout click failed for account {}: {}", accountId, e.getMessage());
            msg = "Ingame logout click failed (" + e.getMessage() + ") — closing session anyway.";
        } finally {
            // Whatever happened above, tear the browser down so the user's
            // mental model ("I clicked Log off, it's over") matches reality.
            sessionManager.release(accountId);
        }

        accountService.recordLoginAttempt(accountId, "LOGGED_OUT", msg);
        botEventService.record(accountId, null, "LOGOUT", BotEventOutcome.OK, msg);
        return LoginResultResponse.success(msg);
    }

    /**
     * Kills the current browser and logs back in with fresh credentials. The
     * shutdown is deliberately the cheap variant — we're throwing the browser
     * away, no need to burn an ingame logout click.
     */
    public LoginResultResponse restart(Long accountId) {
        sessionManager.release(accountId);
        botEventService.record(accountId, null, "RESTART", BotEventOutcome.OK, "Restart requested — relogging.");
        return login(accountId);
    }

    private void tryAutoScrape(Long accountId) {
        ScrapingService scraper = scrapingServiceProvider.getIfAvailable();
        if (scraper != null) {
            try {
                // scrapeAllVillages primes by scraping the current (sidebar-fed) village
                // first, which is what populates the Village rows on a BRAND-NEW account,
                // then loops through every persisted Village to refresh resources +
                // buildings. That makes a first-time login immediately consistent with
                // what "Reload all villages" would produce — the user doesn't have to
                // manually hit a button before the villages list is usable.
                //
                // Subsequent logins pay N extra dorf1 requests vs. the old "current only"
                // behaviour, but that's a once-per-session cost (login is not hot) and is
                // cheap enough to keep things simple. The scheduler handles per-village
                // freshness after that.
                int scraped = scraper.scrapeAllVillages(accountId);
                log.info("Auto-scrape of all villages succeeded for account {} ({} villages)",
                        accountId, scraped);
            } catch (Exception e) {
                // Don't overwrite the recorded SUCCESS of login — auto-scrape is a bonus, not a contract.
                log.warn("Auto-scrape after login failed for account {}: {}", accountId, e.getMessage());
            }
        } else {
            log.debug("Village auto-scrape skipped — ScrapingService not available");
        }

        // Farmlist auto-scrape — same best-effort semantics. Runs after the village
        // scrape so the UI gets the village first (cheaper) and farmlists trickle
        // in a beat later. Failures here must NOT affect the login outcome.
        FarmlistScrapingService farmlistScraper = farmlistScrapingServiceProvider.getIfAvailable();
        if (farmlistScraper != null) {
            try {
                int count = farmlistScraper.scrapeFarmlists(accountId);
                log.info("Auto-scrape of farmlists succeeded for account {} ({} lists)", accountId, count);
            } catch (Exception e) {
                log.warn("Farmlist auto-scrape after login failed for account {}: {}", accountId, e.getMessage());
            }
        } else {
            log.debug("Farmlist auto-scrape skipped — FarmlistScrapingService not available");
        }

        // Tribe auto-detection — cheap one-shot scrape of /profile. No-op when the
        // tribe is already known on this account, so repeated logins don't thrash.
        // Must not affect the login outcome if it fails — ProfileScraper already
        // swallows its own exceptions, but we belt-and-brace the call site too.
        ProfileScraper profileScraper = profileScraperProvider.getIfAvailable();
        if (profileScraper != null) {
            try {
                profileScraper.detectAndPersistIfMissing(accountId);
            } catch (Exception e) {
                log.warn("Tribe auto-detect after login failed for account {}: {}", accountId, e.getMessage());
            }
        } else {
            log.debug("Tribe auto-detect skipped — ProfileScraper not available");
        }
    }
}
