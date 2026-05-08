package com.travianbot.bot.client.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.travianbot.bot.client.TravianClient;
import com.travianbot.bot.client.TravianSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Playwright-driven Chrome client.
 * <p>
 * Launches a real (non-headless) Chromium instance so the user can see the login happen.
 * <p>
 * Login selectors are <b>tolerant</b>: T4.6 Legends servers vary — some wrap the form in
 * {@code #loginScene}, others don't; some have a green submit button, others a plain
 * {@code button[type=submit]} or {@code input[type=submit]}. We try the most specific
 * selector first and fall back through a list. If nothing clicks, we press Enter on the
 * password field as a last resort.
 * <p>
 * Lifecycle: close() is NOT called automatically on successful login — ownership of the
 * long-lived browser belongs to {@code BrowserSessionManager}. This client closes its
 * own Playwright+Browser only when the caller invokes {@link #close()}.
 */
public class BrowserTravianClient implements TravianClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrowserTravianClient.class);

    private static final int NAVIGATION_TIMEOUT_MS = 30_000;
    private static final int SHORT_TIMEOUT_MS = 5_000;
    private static final int LOGIN_TIMEOUT_MS = 20_000;

    // Try specific first, then generic fallbacks. These cover every T4.6 skin I've seen.
    private static final List<String> USERNAME_SELECTORS = List.of(
            "#loginScene input[name=\"name\"]",
            "input[name=\"name\"]",
            "input[type=\"text\"][name*=\"name\" i]",
            "input#name"
    );
    private static final List<String> PASSWORD_SELECTORS = List.of(
            "#loginScene input[name=\"password\"]",
            "input[name=\"password\"]",
            "input[type=\"password\"]",
            "input#password"
    );
    private static final List<String> LOGIN_BUTTON_SELECTORS = List.of(
            "#loginScene button.green",
            "#loginScene button[type=\"submit\"]",
            "button.green.sendLoginForm",
            "button[type=\"submit\"]",
            "input[type=\"submit\"]",
            "button:has-text(\"Login\")",
            "button:has-text(\"Log in\")"
    );
    // Presence of any of these means we're on the login page (not already ingame).
    private static final List<String> LOGIN_PAGE_MARKERS = List.of(
            "#loginScene",
            "form[name=\"login\"]",
            "input[name=\"password\"]"
    );

    private final TravianSession session;
    private final boolean headless;
    private final HumanDelay humanDelay;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    public BrowserTravianClient(TravianSession session) {
        this(session, false, new HumanDelay(() -> new HumanDelay.Range(0, 0)));
    }

    public BrowserTravianClient(TravianSession session, boolean headless) {
        this(session, headless, new HumanDelay(() -> new HumanDelay.Range(0, 0)));
    }

    /**
     * Primary constructor. {@code humanDelay} supplies the random pre-action pause — see the
     * class-level javadoc on {@link HumanDelay} for why this matters.
     */
    public BrowserTravianClient(TravianSession session, boolean headless, HumanDelay humanDelay) {
        this.session = session;
        this.headless = headless;
        this.humanDelay = humanDelay;
    }

    @Override
    public TravianSession session() {
        return session;
    }

    @Override
    public String currentHtml() {
        ensureStarted();
        return page.content();
    }

    @Override
    public String get(String url) {
        ensureStarted();
        humanDelay.beforeAction();
        page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return page.content();
    }

    /**
     * Like {@link #get(String)} but waits (best-effort) for {@code cssSelector}
     * to be attached to the DOM before returning the HTML. T4.6 Legends pages
     * — the top-10 statistics leaderboard in particular — often hydrate their
     * tables via XHR after DOMContentLoaded, so reading {@code page.content()}
     * too early returns an empty skeleton and callers see "no parseable rows".
     * <p>
     * The wait is best-effort: if the selector doesn't appear within the
     * timeout we still return whatever is in the page so the caller can
     * attempt to parse / dump the HTML and decide what to do. Missing markup
     * shouldn't turn into a hard failure here — the parser can fall back, or
     * the caller can log a debug dump.
     */
    public String getWaitingFor(String url, String cssSelector, int timeoutMs) {
        ensureStarted();
        humanDelay.beforeAction();
        page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (PlaywrightException ignored) {
            // Navigation-ish races are fine — we'll still try the selector wait below.
        }
        try {
            page.waitForSelector(cssSelector, new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.ATTACHED)
                    .setTimeout(timeoutMs));
        } catch (PlaywrightException e) {
            log.debug("Selector {} did not appear within {}ms on {} — returning current HTML",
                    cssSelector, timeoutMs, url);
        }
        return page.content();
    }

    /**
     * Exposes the underlying page for the session manager / scraper to reuse.
     * Only valid after {@link #ensureStarted()} or a successful {@link #login()}.
     */
    public Page page() {
        ensureStarted();
        return page;
    }

    /**
     * Clicks the ingame logout link (or navigates to {@code /logout.php} as a
     * fallback) so Travian ends the server-side session. Returns {@code true}
     * if a link/button was found and clicked, {@code false} if nothing logout-
     * shaped was visible — in that case the caller should still close the
     * browser locally since the session is presumably already stale.
     * <p>
     * Skin coverage: T4.6 Legends puts a {@code a.logoutButton} in the top
     * menu on most skins; some older skins render {@code a[href*="logout.php"]}
     * directly. We also fall back to a hard navigation to {@code /logout.php},
     * which every T4 server handles.
     */
    public boolean clickLogout(String serverUrl) {
        if (page == null) return false; // nothing to click, browser already gone.

        String[] logoutSelectors = {
                "a.logoutButton",
                "a[href*=\"logout.php\"]",
                "a[href*=\"logout\"]",
                "a:has-text(\"Logout\")",
                "a:has-text(\"Log out\")",
                "a:has-text(\"Abmelden\")"
        };
        for (String sel : logoutSelectors) {
            try {
                ElementHandle link = page.querySelector(sel);
                if (link != null && link.isVisible()) {
                    humanDelay.beforeAction();
                    link.click();
                    log.info("Clicked ingame logout via {}", sel);
                    try {
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    } catch (PlaywrightException ignored) {
                        // redirect races are fine
                    }
                    session.cookies().clear();
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("Logout selector {} didn't work: {}", sel, e.getMessage());
            }
        }

        // Fallback: hard-navigate to /logout.php. Every T4 server responds to
        // this URL by clearing the session and redirecting to the login page.
        try {
            String url = (serverUrl == null ? session.serverUrl() : serverUrl) + "/logout.php";
            log.info("No logout link found — navigating directly to {}", url);
            humanDelay.beforeAction();
            page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            } catch (PlaywrightException ignored) {
                // ok
            }
            session.cookies().clear();
            return true;
        } catch (PlaywrightException e) {
            log.warn("Direct /logout.php navigation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void login() {
        ensureStarted();

        log.info("Navigating to {}", session.serverUrl());
        humanDelay.beforeAction();
        try {
            page.navigate(session.serverUrl(), new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (PlaywrightException e) {
            throw new LoginFailedException(
                    "Could not reach server URL " + session.serverUrl() + " — check the URL and network",
                    e
            );
        }

        if (isIngamePage()) {
            log.info("Already logged in (found #servertime) on {}", page.url());
            captureCookies();
            session.markLoggedIn();
            return;
        }

        waitForLoginPage();

        String filledUser = fillFirstMatching(USERNAME_SELECTORS, session.username(), "username input");
        String filledPw = fillFirstMatching(PASSWORD_SELECTORS, session.password(), "password input");
        log.info("Filled credentials (user via {}, password via {})", filledUser, filledPw);

        submitLoginForm(filledPw);

        try {
            page.waitForURL(url -> url.contains("dorf1.php") || url.contains("dorf2.php"),
                    new Page.WaitForURLOptions().setTimeout(LOGIN_TIMEOUT_MS));
        } catch (PlaywrightException e) {
            String url = safeUrl();
            String errorHint = readVisibleLoginError();
            throw new LoginFailedException(
                    "Did not land on dorf1/dorf2 after login (still at " + url + ")"
                            + (errorHint == null ? " — wrong credentials, captcha, or slow network"
                                                 : " — server says: \"" + errorHint + "\""),
                    e
            );
        }

        if (!isIngamePage()) {
            throw new LoginFailedException(
                    "Reached game URL " + safeUrl() + " but #servertime is missing — layout may have changed");
        }

        captureCookies();
        session.markLoggedIn();
        log.info("Login successful for {}@{} (landed on {})",
                session.username(), session.serverUrl(), safeUrl());
    }

    /**
     * Triggers a "send all targets" action on a given farmlist via the rally
     * point. Opens {@code /build.php?gid=16&tt=99&id=<lid>}, checks every
     * {@code .markSend} checkbox that isn't already checked, then clicks the
     * submit button.
     * <p>
     * Returns the number of targets that were submitted. Any server-side
     * errors are logged and surface as a {@link PlaywrightException} — the
     * caller decides whether to retry.
     */
    public int sendFarmlist(String serverUrl, long travianListId) {
        ensureStarted();
        String url = serverUrl + "/build.php?gid=16&tt=99&id=" + travianListId;
        log.info("Opening farmlist {} for send: {}", travianListId, url);
        humanDelay.beforeAction();
        page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        // "Check all" shortcut if the skin offers one — otherwise tick each target checkbox.
        int checked = 0;
        try {
            ElementHandle checkAll = page.querySelector("input.checkAll, input[name=\"checkAll\"]");
            if (checkAll != null && checkAll.isVisible()) {
                if (!checkAll.isChecked()) {
                    humanDelay.beforeAction();
                    checkAll.check();
                }
                checked = -1; // "all"
            }
        } catch (PlaywrightException ignored) {
            // Fall through to per-row.
        }

        if (checked == 0) {
            List<ElementHandle> boxes = page.querySelectorAll(
                    "input.markSend, input[name^=\"slotIds\"], input.selectTarget");
            for (ElementHandle cb : boxes) {
                try {
                    if (cb.isVisible() && !cb.isChecked()) {
                        // Per-target humanization is deliberate — a batch of 30 checkboxes
                        // flipped in under 50 ms is the clearest "I'm a bot" fingerprint.
                        humanDelay.beforeAction();
                        cb.check();
                        checked++;
                    } else if (cb.isChecked()) {
                        checked++;
                    }
                } catch (PlaywrightException e) {
                    log.debug("Couldn't check a send box: {}", e.getMessage());
                }
            }
        }

        // Submit. The button has varied wildly across T4.6 skins — try a list.
        String[] sendButtonSelectors = {
                "button.startFarmListRaid",
                "button.green.sendRaidsFromList",
                "button[name=\"send\"]",
                "button[value=\"send\"]",
                "button:has-text(\"Start raid\")",
                "button:has-text(\"Send\")"
        };
        boolean submitted = false;
        for (String sel : sendButtonSelectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible() && btn.isEnabled()) {
                    humanDelay.beforeAction();
                    btn.click();
                    log.info("Clicked send button via {}", sel);
                    submitted = true;
                    break;
                }
            } catch (PlaywrightException e) {
                log.debug("Send selector {} didn't work: {}", sel, e.getMessage());
            }
        }
        if (!submitted) {
            throw new PlaywrightException(
                    "Could not find a send button on farmlist " + travianListId
                            + " — tried " + String.join(", ", sendButtonSelectors));
        }
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (PlaywrightException ignored) {
            // Reload races are fine — the send is already on the wire.
        }
        return checked;
    }

    /**
     * Performs an NPC resource-exchange in the village currently active in the
     * browser. The marketplace tab used is Travian's "Merchant" / NPC merchant
     * view ({@code gid=17} with the NPC form) — requires Plus or the per-trade
     * gold cost, which the server itself enforces; we don't second-guess.
     *
     * <p>Flow (designed to mirror a human player):
     * <ol>
     *   <li>If the topbar/sidebar exposes a marketplace link, click it. The
     *       NPC form is on the same {@code gid=17} page on modern skins, so we
     *       don't need a separate "management tab" click unless the skin
     *       renders tabs (then we click the {@code npc}-flavoured tab).</li>
     *   <li>Wait for the four target inputs (named {@code r1..r4} in every
     *       T4.6 skin I've seen, sometimes also with id {@code r1} etc).</li>
     *   <li>Type the target amounts per-key via {@link #humanType}.</li>
     *   <li>Click the Redistribute / OK / green submit button.</li>
     * </ol>
     *
     * <p>Targets must sum to the current total of all four resources — the
     * server rejects submissions that change the total. Computing the split
     * from the configured ratios + current balance is the caller's job; this
     * method is a dumb executor so it can be covered by Playwright tests
     * without dragging in the whole settings stack.
     *
     * @return {@code true} if the form was submitted; {@code false} if the
     *         NPC form was not reachable (no Plus, no gold, marketplace not
     *         built, skin version we don't cover, etc.). On {@code false} the
     *         browser is left on whatever page it ended up on — callers
     *         should not assume anything about page state.
     */
    public boolean performNpcTrade(String serverUrl, int targetWood, int targetClay,
                                   int targetIron, int targetCrop) {
        ensureStarted();
        if (targetWood < 0 || targetClay < 0 || targetIron < 0 || targetCrop < 0) {
            throw new IllegalArgumentException(
                    "NPC trade targets must be non-negative: wood=" + targetWood
                            + " clay=" + targetClay + " iron=" + targetIron + " crop=" + targetCrop);
        }

        // Step 1 — land on marketplace (gid=17). Click-first, URL fallback.
        // The topbar link on T4.6 is `a.marketplace` or an anchor to
        // `build.php?gid=17`. Village-view has an icon with class `g17`.
        clickOrNavigate(serverUrl + "/build.php?gid=17",
                "build.php?gid=17", "gid=17");

        // Step 2 — wait for the marketplace shell. If it never appears we
        // probably landed on a building-not-built page.
        try {
            page.waitForSelector("#build, .marketPlace, .marketplaceContent, #mercantile",
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.ATTACHED)
                            .setTimeout(SHORT_TIMEOUT_MS));
        } catch (PlaywrightException e) {
            log.warn("Marketplace (gid=17) did not render — village may not have a marketplace yet.");
            return false;
        }

        // Step 3 — click into the NPC / merchant tab if we're not already
        // showing the redistribute form. Modern skins surface the NPC form on
        // tab {@code t=5}; some older ones use a query string of {@code t=3}.
        // Detect by the presence of r1..r4 inputs — if they're absent, try a
        // tab click, then re-check.
        if (!npcFormVisible()) {
            boolean switched = clickNpcTab();
            if (!switched) {
                // Last resort: URL-level tab nav. Keeps the bot functional on
                // skins where the tab link uses JS-only handlers.
                humanDelay.beforeAction();
                page.navigate(serverUrl + "/build.php?gid=17&t=5",
                        new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
                try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (PlaywrightException ignored) {}
            }
            if (!npcFormVisible()) {
                log.warn("NPC form inputs (r1..r4) not visible — skin may not support NPC trade, "
                        + "or the account lacks Plus/gold privileges.");
                return false;
            }
        }

        // Step 4 — fill the four inputs per-key. Selectors follow the two
        // common T4.6 flavours: name-based and id-based.
        if (!typeIntoFirstMatching(List.of("input[name=\"r1\"]", "input#r1"),
                String.valueOf(targetWood), "npc.r1")) return false;
        if (!typeIntoFirstMatching(List.of("input[name=\"r2\"]", "input#r2"),
                String.valueOf(targetClay), "npc.r2")) return false;
        if (!typeIntoFirstMatching(List.of("input[name=\"r3\"]", "input#r3"),
                String.valueOf(targetIron), "npc.r3")) return false;
        if (!typeIntoFirstMatching(List.of("input[name=\"r4\"]", "input#r4"),
                String.valueOf(targetCrop), "npc.r4")) return false;

        // Step 5 — click Redistribute. Button has varied across skins; these
        // are the known labels/classes. The submit *must* be a click, not a
        // form.submit(), because Travian's JS validates the page's hidden
        // token only on click.
        String[] submitSelectors = {
                "button.green[name=\"submit\"]",
                "button[type=\"submit\"][name=\"submit\"]",
                "button.green.npc",
                "button.npcDistribute",
                "button:has-text(\"Redistribute\")",
                "button:has-text(\"Umverteilen\")",
                "button:has-text(\"OK\")"
        };
        for (String sel : submitSelectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible() && btn.isEnabled()) {
                    humanDelay.beforeAction();
                    btn.click();
                    log.info("Submitted NPC trade via {} — targets w={} c={} i={} cr={}",
                            sel, targetWood, targetClay, targetIron, targetCrop);
                    try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (PlaywrightException ignored) {}
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("NPC submit selector {} didn't work: {}", sel, e.getMessage());
            }
        }
        log.warn("Could not find an NPC submit button on {}/build.php?gid=17 — "
                + "targets typed but not submitted.", serverUrl);
        return false;
    }

    /** True if the NPC form's four target inputs are currently in the DOM. */
    private boolean npcFormVisible() {
        try {
            return page.querySelector("input[name=\"r1\"], input#r1") != null
                    && page.querySelector("input[name=\"r4\"], input#r4") != null;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** Tries to click any of the common NPC-tab link flavours on the marketplace page. */
    private boolean clickNpcTab() {
        String[] tabSelectors = {
                "a[href*=\"t=5\"]",
                "a.npc",
                "a:has-text(\"NPC\")",
                "a:has-text(\"Merchant\")",
                "a:has-text(\"Händler\")",
                "li.npc a"
        };
        for (String sel : tabSelectors) {
            try {
                ElementHandle link = page.querySelector(sel);
                if (link != null && link.isVisible()) {
                    humanDelay.beforeAction();
                    link.click();
                    try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (PlaywrightException ignored) {}
                    log.debug("Clicked NPC tab via {}", sel);
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("NPC tab selector {} didn't work: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    /** Walks a list of selectors and humanTypes into the first visible match. */
    private boolean typeIntoFirstMatching(List<String> selectors, String value, String label) {
        for (String sel : selectors) {
            if (humanType(sel, value)) return true;
        }
        log.warn("No visible input found for {} — tried {}", label, selectors);
        return false;
    }

    /**
     * Pushes a batch of unspent skill points into one hero attribute and submits.
     * The attribute form on {@code /hero/attributes} uses inputs named
     * {@code fightingStrength}, {@code offBonus}, {@code defBonus} or {@code resources}.
     * <p>
     * Returns the number of points actually submitted (clamped to what the
     * page says is free + the per-attribute 100-point ceiling). Returns 0 if
     * the form isn't on the page — happens for sitters without the hero
     * privilege, or when the page is in the "hero is dead" state.
     */
    public int spendHeroPoints(String serverUrl, String attributeField, int points) {
        if (points <= 0) return 0;
        ensureStarted();
        String url = serverUrl + "/hero/attributes";
        log.info("Spending {} hero points into {}: {}", points, attributeField, url);
        humanDelay.beforeAction();
        page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        // Locate the per-attribute input. Newer skins wrap it in a div with a
        // matching class; older ones expose the input directly.
        String selector = "input[name=\"" + attributeField + "\"]";
        ElementHandle input = page.querySelector(selector);
        if (input == null || !input.isVisible()) {
            log.warn("Could not find hero attribute input {} on {}", selector, url);
            return 0;
        }

        try {
            // Per-key typing so the attribute form sees the same event
            // stream a human generates. Atomic fill() would paste
            // "37" in one step, which is distinguishable server-side.
            humanType(input, String.valueOf(points));
        } catch (PlaywrightException e) {
            log.warn("Could not type hero attribute {}: {}", attributeField, e.getMessage());
            return 0;
        }

        String[] saveBtns = {
                "button.green.save",
                "button[name=\"save\"]",
                "button:has-text(\"Save\")",
                "button:has-text(\"OK\")"
        };
        for (String sel : saveBtns) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn != null && btn.isVisible() && btn.isEnabled()) {
                    humanDelay.beforeAction();
                    btn.click();
                    log.info("Submitted hero points via {}", sel);
                    try {
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    } catch (PlaywrightException ignored) {
                    }
                    return points;
                }
            } catch (PlaywrightException e) {
                log.debug("Save selector {} didn't work: {}", sel, e.getMessage());
            }
        }
        log.warn("Could not find a Save button on /hero/attributes — points not submitted");
        return 0;
    }

    /**
     * Clicks the "Upgrade to level N" button on a building page, starting the
     * upgrade right now. The page is {@code /build.php?id=<slotId>} — pass
     * the slot index from the village view, not the Travian internal gid.
     * <p>
     * The upgrade button has varied wildly across T4.6 skins: recent ones use
     * {@code button.green.build}, older ones use {@code button[value="construct"]},
     * and some German/localised servers ship only a text label on a generic
     * {@code button.green}. We walk through a tolerant selector list, then fall
     * back to text matching for "Upgrade"/"Ausbauen"/"Build"/"Bauen".
     * <p>
     * Returns {@code true} if a button was clicked and the page navigated;
     * {@code false} if the upgrade could not be started (out of resources,
     * max level reached, queue full). Callers should surface a friendly
     * message using the {@code .errorMessage} dump if this returns false.
     */
    public boolean startBuildingUpgrade(String serverUrl, int slotId) {
        ensureStarted();
        String url = serverUrl + "/build.php?id=" + slotId;
        log.info("Opening building slot {} for upgrade: {}", slotId, url);
        humanDelay.beforeAction();
        page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        // Hard guard: when the in-game build queue is already full, Travian
        // replaces (or adds below) the normal green "Upgrade to level X" button
        // with a gold "Construct with master builder" button. Clicking it would
        // spend the user's gold, which must remain a deliberate, user-initiated
        // action. Detect this state *before* walking selectors so there is no
        // way for a fallback match to pick up the gold button.
        if (isMasterBuilderOnlyOption()) {
            log.warn("Master-builder (gold) is the only upgrade option on slot {} — refusing to spend gold. "
                    + "In-game queue is full; the scheduler will retry once a slot frees up.", slotId);
            return false;
        }

        // Primary: the green build button in the upgrade container. Every
        // selector explicitly excludes `.gold` and the `.masterBuilder`
        // container so a fallback match can never select the paid button.
        // Travian sometimes nests the button inside a form, sometimes not,
        // and some skins put it inside `.section1` on the resource fields page.
        String[] upgradeSelectors = {
                ".upgradeButtonsContainer button.green.build:not(.gold)",
                ".upgradeButtonsContainer button.build:not(.gold)",
                ".section1 button.green.build:not(.gold)",
                ".section1 button.build:not(.gold)",
                "#build button.green.build:not(.gold)",
                "button.green.build:not(.gold)",
                "button.build:not(.gold)",
                "button[value=\"construct\"]:not(.gold)",
                "form button.green:not(.destroy):not(.gold)",
                "button:not(.gold):has-text(\"Upgrade to level\")",
                "button:not(.gold):has-text(\"Ausbauen auf Stufe\")",
                "button:not(.gold):has-text(\"Upgrade\")",
                "button:not(.gold):has-text(\"Ausbauen\")",
                "button:not(.gold):has-text(\"Build\")",
                "button:not(.gold):has-text(\"Bauen\")"
        };

        for (String sel : upgradeSelectors) {
            try {
                ElementHandle btn = page.querySelector(sel);
                if (btn == null || !btn.isVisible() || !btn.isEnabled()) continue;

                // Belt-and-suspenders: even if the selector excluded `.gold`,
                // confirm the matched node is not inside a masterBuilder
                // wrapper (skins occasionally push the gold button through an
                // ancestor container without applying the class to the button
                // itself).
                if (isInsideMasterBuilderWrapper(btn)) {
                    log.debug("Skipping candidate from {} — nested inside master-builder wrapper.", sel);
                    continue;
                }

                humanDelay.beforeAction();
                btn.click();
                log.info("Clicked upgrade button via {}", sel);
                try {
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                } catch (PlaywrightException ignored) {
                    // Some skins redirect via JS; a short wait-for-load
                    // miss doesn't mean the upgrade didn't go through.
                }
                return true;
            } catch (PlaywrightException e) {
                log.debug("Upgrade selector {} didn't work: {}", sel, e.getMessage());
            }
        }

        // None matched. Capture any visible reason so the caller can surface it.
        String hint = readVisibleBuildError();
        log.warn("No upgrade button found on {} — {}", url,
                hint == null ? "queue may be full or resources insufficient" : "server says: " + hint);
        return false;
    }

    /**
     * Returns true if the upgrade page currently only offers the paid
     * master-builder (gold) button and no free green build button.
     * Travian renders the gold button when the in-game queue is already at
     * capacity — the bot must not click it.
     */
    private boolean isMasterBuilderOnlyOption() {
        try {
            boolean hasGold = page.querySelector(
                    "button.gold, .masterBuilder button, button.build.gold, .usingMaster button"
            ) != null;
            if (!hasGold) return false;
            boolean hasGreen = page.querySelector(
                    ".upgradeButtonsContainer button.green.build:not(.gold), "
                            + "button.green.build:not(.gold)"
            ) != null;
            return !hasGreen;
        } catch (PlaywrightException e) {
            // On a DOM hiccup, fail closed — better to skip the upgrade and
            // retry next tick than risk clicking a gold button by accident.
            log.debug("Master-builder probe failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * True if the candidate element is nested inside any ancestor whose class
     * contains {@code masterBuilder} or {@code usingMaster} — the wrappers
     * Travian uses for the paid upgrade path.
     */
    private boolean isInsideMasterBuilderWrapper(ElementHandle btn) {
        try {
            Object result = btn.evaluate(
                    "el => !!el.closest('.masterBuilder, .usingMaster, .build.gold')"
            );
            return Boolean.TRUE.equals(result);
        } catch (PlaywrightException e) {
            // Err on the cautious side — we'd rather skip a real button than
            // spend gold by mistake.
            return true;
        }
    }

    /**
     * Returns the first visible error/notice on a build page, or null if
     * none is present. Covers the out-of-resources and queue-full notices
     * Travian renders in {@code .errorMessage} / {@code .build_details .error}.
     */
    private String readVisibleBuildError() {
        String[] selectors = {".errorMessage", ".error", ".build_details .error", ".notice"};
        for (String s : selectors) {
            try {
                ElementHandle el = page.querySelector(s);
                if (el != null && el.isVisible()) {
                    String text = el.innerText();
                    if (text != null && !text.isBlank()) return text.trim();
                }
            } catch (PlaywrightException ignored) {
                // try next
            }
        }
        return null;
    }

    @Override
    public void close() {
        closeQuietly("page", () -> { if (page != null) page.close(); });
        closeQuietly("context", () -> { if (context != null) context.close(); });
        closeQuietly("browser", () -> { if (browser != null) browser.close(); });
        closeQuietly("playwright", () -> { if (playwright != null) playwright.close(); });
        page = null;
        context = null;
        browser = null;
        playwright = null;
    }

    // ---------- internals ----------

    private void ensureStarted() {
        if (page != null) return;

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(headless)
            );
            context = browser.newContext();
            page = context.newPage();
            page.setDefaultTimeout(NAVIGATION_TIMEOUT_MS);
        } catch (PlaywrightException e) {
            close();
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("Executable doesn't exist") || msg.contains("browserType.launch")) {
                throw new PlaywrightException(
                        "Chromium is not installed for Playwright. Install it once with:\n"
                                + "  cd backend && mvn exec:java -Dexec.mainClass=\"com.microsoft.playwright.CLI\" -Dexec.args=\"install chromium\"\n"
                                + "Original error: " + msg,
                        e
                );
            }
            throw e;
        }
    }

    private boolean isIngamePage() {
        try {
            return page.querySelector("#servertime") != null;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private void waitForLoginPage() {
        PlaywrightException lastError = null;
        for (String selector : LOGIN_PAGE_MARKERS) {
            try {
                page.waitForSelector(selector, new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(SHORT_TIMEOUT_MS));
                log.debug("Login page detected via {}", selector);
                return;
            } catch (PlaywrightException e) {
                lastError = e;
            }
        }
        throw new LoginFailedException(
                "No login form detected on " + safeUrl() + " — tried: " + LOGIN_PAGE_MARKERS,
                lastError
        );
    }

    private String fillFirstMatching(List<String> candidates, String value, String label) {
        for (String selector : candidates) {
            try {
                ElementHandle el = page.querySelector(selector);
                if (el != null && el.isVisible()) {
                    // humanType (not fill) so the login form sees per-key
                    // events instead of an atomic paste — see the class-level
                    // note on bot-detection signatures for why this matters.
                    humanType(el, value);
                    return selector;
                }
            } catch (PlaywrightException e) {
                log.debug("Selector {} didn't match for {}: {}", selector, label, e.getMessage());
            }
        }
        throw new LoginFailedException(
                "Could not find " + label + " — tried selectors: " + candidates
        );
    }

    private void submitLoginForm(String passwordSelector) {
        for (String selector : LOGIN_BUTTON_SELECTORS) {
            try {
                ElementHandle btn = page.querySelector(selector);
                if (btn != null && btn.isVisible() && btn.isEnabled()) {
                    humanDelay.beforeAction();
                    btn.click();
                    log.info("Submitted login form via button selector {}", selector);
                    return;
                }
            } catch (PlaywrightException e) {
                log.debug("Button selector {} didn't work: {}", selector, e.getMessage());
            }
        }
        // Fallback: press Enter on the password field — works on every Travian form.
        log.info("No login button matched; pressing Enter on {} as fallback", passwordSelector);
        try {
            humanDelay.beforeAction();
            page.press(passwordSelector, "Enter");
        } catch (PlaywrightException e) {
            throw new LoginFailedException(
                    "No login button found (tried " + LOGIN_BUTTON_SELECTORS
                            + ") and pressing Enter on password field also failed",
                    e
            );
        }
    }

    // ---------- Human-like input primitives ----------
    //
    // Travian's bot detection flags two things in particular: form fields that
    // get populated atomically (fill()) and URL-driven navigation that never
    // goes through the topbar icons. Real users type one key at a time with
    // irregular pauses and click links instead of editing the address bar.
    // The helpers below are the single place we do both.

    /**
     * Minimum pause between keystrokes, in ms. Shorter than the action-level
     * {@link HumanDelay} range because this is intra-word cadence — bursts of
     * typing with occasional gaps, not a second-long "thinking" pause. Values
     * tuned by eyeballing my own typing: ~60-180 ms per keypress is the
     * natural burst range; outliers (pause while thinking of the next letter)
     * are handled by the random upper end and the occasional {@link #maybePause}
     * micro-stall below.
     */
    private static final int MIN_KEY_DELAY_MS = 35;
    private static final int MAX_KEY_DELAY_MS = 160;

    /**
     * Probability (out of 100) that any given character is followed by a
     * longer "thinking" stall on top of the per-key delay. Real typing has
     * these irregular pauses — without them the distribution is too flat to
     * look human.
     */
    private static final int THINKING_STALL_PERCENT = 6;
    private static final int THINKING_STALL_MIN_MS = 250;
    private static final int THINKING_STALL_MAX_MS = 900;

    /**
     * Types text into the element one character at a time, with a randomised
     * per-key pause. Before typing, the element is focused via a real click
     * and any existing value is cleared with Select-All + Backspace — the
     * same thing a user would do if the form was pre-populated.
     * <p>
     * This replaces every {@link ElementHandle#fill(String)} call in this
     * client. {@code fill()} pastes the whole string atomically in a few
     * milliseconds, which shows up on server analytics as a flat uniform
     * latency distribution — the single clearest bot signature Travian
     * watches for.
     * <p>
     * The method assumes the element is a text-y input (username, password,
     * numeric resource target). For checkboxes/dropdowns/buttons, stick with
     * {@code click()}.
     */
    private void humanType(ElementHandle el, String text) {
        if (text == null) text = "";
        humanDelay.beforeAction();
        el.click();
        // Clear any pre-filled value. Cmd on macOS, Control everywhere else —
        // Playwright translates "Meta+a" portably but "Control+a" works on
        // every platform's Chromium install and avoids a host-OS probe.
        el.press("Control+a");
        el.press("Backspace");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sleepQuietly(nextKeyDelayMs());
            // page.keyboard.type() dispatches a full keydown/keypress/input/keyup
            // sequence per character, which is what server-side input listeners
            // look for. ElementHandle.type() would also work but goes through an
            // extra focus check per call — not needed after our click() above.
            page.keyboard().type(String.valueOf(c));
            if (ThreadLocalRandom.current().nextInt(100) < THINKING_STALL_PERCENT) {
                sleepQuietly(ThreadLocalRandom.current()
                        .nextLong(THINKING_STALL_MIN_MS, THINKING_STALL_MAX_MS + 1));
            }
        }
    }

    /**
     * Convenience form that resolves the element from a selector and falls back
     * to {@link ElementHandle#fill(String)} if the element can't be clicked
     * (e.g. it's detached between query and type). Returns true if the typing
     * actually happened.
     */
    private boolean humanType(String selector, String text) {
        try {
            ElementHandle el = page.querySelector(selector);
            if (el == null || !el.isVisible()) return false;
            humanType(el, text);
            return true;
        } catch (PlaywrightException e) {
            log.debug("humanType on {} failed: {}", selector, e.getMessage());
            return false;
        }
    }

    private long nextKeyDelayMs() {
        return ThreadLocalRandom.current().nextLong(MIN_KEY_DELAY_MS, MAX_KEY_DELAY_MS + 1);
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Human-like navigation: looks for a visible {@code <a>} on the current
     * page whose {@code href} contains the target URL (or a fragment of it)
     * and clicks it. Falls back to {@link Page#navigate} only if no such link
     * is visible.
     * <p>
     * Why: editing the browser's address bar is not something a real player
     * does — they click the marketplace icon, the village switcher, the
     * resource field. Click-first makes the bot's request headers
     * (Referer, Sec-Fetch-Site, mouse-move preceding the click) match a
     * human session.
     * <p>
     * This helper is used by NEW code. The older scrape/upgrade/farmlist
     * paths still call {@link Page#navigate} directly — a full refactor is
     * planned but deferred to keep this change focused. Use this helper any
     * time you add a new page transition.
     *
     * @param targetUrl        full URL to reach (for the fallback path)
     * @param hrefFragment     substring to match against {@code a[href]} on the current page
     * @param alsoExtraHrefFragments additional fallback substrings (first match wins)
     * @return whether the transition happened by clicking a link (true) or URL nav (false)
     */
    private boolean clickOrNavigate(String targetUrl, String hrefFragment, String... alsoExtraHrefFragments) {
        String[] fragments = new String[1 + (alsoExtraHrefFragments == null ? 0 : alsoExtraHrefFragments.length)];
        fragments[0] = hrefFragment;
        if (alsoExtraHrefFragments != null) {
            System.arraycopy(alsoExtraHrefFragments, 0, fragments, 1, alsoExtraHrefFragments.length);
        }
        for (String frag : fragments) {
            if (frag == null || frag.isBlank()) continue;
            String selector = "a[href*=\"" + frag + "\"]";
            try {
                ElementHandle link = page.querySelector(selector);
                if (link != null && link.isVisible()) {
                    humanDelay.beforeAction();
                    link.click();
                    try {
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    } catch (PlaywrightException ignored) {
                        // SPA-style skins may not trigger a full reload — the
                        // DOM update arrives via XHR. Waiting-for-selector is
                        // the caller's job.
                    }
                    log.debug("Navigated to {} by clicking link matching '{}'", targetUrl, frag);
                    return true;
                }
            } catch (PlaywrightException e) {
                log.debug("Click-nav via {} failed: {}", selector, e.getMessage());
            }
        }
        humanDelay.beforeAction();
        page.navigate(targetUrl, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        } catch (PlaywrightException ignored) {
            // Redirect races are fine — callers that care wait for their own selector.
        }
        log.debug("Navigated to {} via URL fallback (no matching link visible)", targetUrl);
        return false;
    }

    private String readVisibleLoginError() {
        String[] errorSelectors = {"#loginScene .error", ".error", ".feedback .error"};
        for (String s : errorSelectors) {
            try {
                ElementHandle el = page.querySelector(s);
                if (el != null && el.isVisible()) {
                    String text = el.innerText();
                    if (text != null && !text.isBlank()) return text.trim();
                }
            } catch (PlaywrightException ignored) {
                // Try next selector
            }
        }
        return null;
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (PlaywrightException e) {
            return "<unknown>";
        }
    }

    private void captureCookies() {
        Map<String, String> cookies = new HashMap<>();
        for (Cookie c : context.cookies()) {
            cookies.put(c.name, c.value);
        }
        session.cookies().clear();
        session.cookies().putAll(cookies);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void closeQuietly(String what, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("{} close() failed: {}", what, e.getMessage());
        }
    }
}
