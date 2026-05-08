package com.travianbot.bot.scraper;

import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.Tribe;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.account.service.AccountSettingsService;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot scrape of the in-game {@code /profile} page to pull the hero's tribe.
 * <p>
 * Travian skins this page differently across languages and versions. We use
 * three cascading detection strategies, from most reliable to most permissive:
 *
 * <ol>
 *   <li><b>Tribe CSS class</b> — T4.6 tags tribe-owned elements with classes
 *       of the form {@code tribeN} or {@code gN}, where N encodes the tribe
 *       (1=Romans, 2=Teutons, 3=Gauls, 6=Egyptians, 7=Huns, 8=Spartans,
 *       9=Vikings; 4=Nature and 5=Natars are NPCs we ignore). This is
 *       language-agnostic.</li>
 *   <li><b>Tribe image URL</b> — the profile page usually embeds
 *       {@code /img/u/tribes/N.png} or {@code /img/u/3.png} where N is the
 *       same tribe id. Also language-agnostic.</li>
 *   <li><b>English "Tribe …" text</b> — fallback for legacy / English skins
 *       where neither class nor image encodes the id. Narrowly scoped to
 *       avoid false positives from nav links and menu items.</li>
 * </ol>
 *
 * This scraper is intentionally lightweight: no DB writes of its own, no
 * transactional boundary. The caller (typically the post-login hook in
 * {@link com.travianbot.account.service.LoginService}) decides when to persist
 * the result via {@link AccountSettingsService#autoDetectTribe}.
 */
@Service
public class ProfileScraper {

    private static final Logger log = LoggerFactory.getLogger(ProfileScraper.class);

    /**
     * Class attribute tribe-id pattern: {@code tribe3}, {@code g3}, {@code unit uhero g3}.
     * Captures the numeric id so we can resolve it against {@link #tribeFromId}.
     * We deliberately match on a word boundary to avoid picking up {@code g30} or
     * unrelated classes like {@code tribe-log}. The two leading-character
     * alternatives ({@code g} and {@code tribe}) cover every T4.6 skin variant
     * I've seen — add more here if a server uses something novel.
     */
    private static final Pattern TRIBE_CLASS_PATTERN =
            Pattern.compile("\\b(?:tribe|g)([1-9])\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Tribe image URL pattern: matches paths that clearly name the "tribes"
     * directory — e.g. {@code /img/u/tribes/3.png}, {@code /tribes/3.gif},
     * {@code assets/tribe/3.png}. The id capture group is validated through
     * {@link #tribeFromId} so we never guess past the known 1..9 range. The
     * {@code tribes?/} requirement dodges false positives from unrelated
     * numeric filenames (troop icons, item icons, event banners) that would
     * otherwise match a looser pattern.
     */
    private static final Pattern TRIBE_IMAGE_URL_PATTERN =
            Pattern.compile("/tribes?/([1-9])\\.(?:png|gif|jpg)", Pattern.CASE_INSENSITIVE);

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final AccountSettingsRepository accountSettingsRepository;
    private final AccountSettingsService accountSettingsService;

    public ProfileScraper(
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            AccountSettingsRepository accountSettingsRepository,
            AccountSettingsService accountSettingsService
    ) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.accountSettingsRepository = accountSettingsRepository;
        this.accountSettingsService = accountSettingsService;
    }

    /**
     * Auto-detect and persist the tribe for {@code accountId} if it isn't already set.
     * No-op when the browser isn't logged in, the tribe is already known, or the page
     * simply doesn't contain a recognisable tribe marker. Never throws — this is a
     * best-effort enrichment and must not take down the login flow.
     *
     * @return the tribe that's now stored (whether we just detected it or it was already there),
     *         or {@code null} if we couldn't figure it out.
     */
    public Tribe detectAndPersistIfMissing(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null) {
            log.debug("Tribe auto-detect skipped for account {}: no open browser session", accountId);
            return null;
        }
        Account account;
        try {
            account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new AccountNotFoundException(accountId));
        } catch (RuntimeException e) {
            log.debug("Tribe auto-detect skipped for account {}: {}", accountId, e.getMessage());
            return null;
        }

        // Early-out: if the tribe is already set, don't burn an HTTP round-trip
        // on /profile just to re-confirm it. The user explicitly asked for
        // "if tribe isn't known, try to load it on login" — so we fetch only
        // on the missing case. Repeated logins stay cheap.
        Tribe already = accountSettingsRepository.findByAccountId(accountId)
                .map(AccountSettings::getTribe)
                .orElse(null);
        if (already != null) {
            log.debug("Tribe auto-detect skipped for account {}: already known ({})", accountId, already);
            return already;
        }

        String url = trimTrailingSlash(account.getServerUrl()) + "/profile";
        String html;
        try {
            // T4.6 Legends' heroV2 profile fetches the details table via XHR
            // after DOMContentLoaded, so a plain page.content() races the
            // hydration and returns the skeleton. Wait for {@code .playerDetails}
            // (or the older {@code .details}) to be attached before reading.
            html = client.getWaitingFor(url, ".playerDetails, .details, #details", 5000);
        } catch (RuntimeException e) {
            log.warn("Tribe auto-detect: failed to fetch {} for account {}: {}", url, accountId, e.getMessage());
            return null;
        }

        Tribe detected = parseTribe(html);
        if (detected == null) {
            // Snip a small chunk of HTML to the log so we can see what the page
            // actually returned — often the fix is "Travian changed the DOM
            // shape" and seeing the real markup saves a round of guessing.
            String snippet = html == null ? "<null>" :
                    html.substring(0, Math.min(html.length(), 1500)).replaceAll("\\s+", " ");
            log.warn("Tribe auto-detect: no tribe marker found on profile for account {} "
                    + "(tried structural, CSS class, image URL, and English text passes). "
                    + "HTML head snippet: {}", accountId, snippet);
            return null;
        }

        try {
            Tribe persisted = accountSettingsService.autoDetectTribe(accountId, detected);
            log.info("Tribe auto-detect: account {} tribe = {}", accountId, persisted);
            return persisted;
        } catch (RuntimeException e) {
            log.warn("Tribe auto-detect: failed to persist {} for account {}: {}", detected, accountId, e.getMessage());
            return null;
        }
    }

    /**
     * Pull the tribe out of a profile-page HTML blob. Package-private for tests.
     * <p>
     * Four cascading passes, ordered most→least reliable:
     * <ol>
     *   <li><b>Structural</b> — a {@code <tr>} whose label cell spells "Tribe"
     *       (case-insensitive) paired with a value cell containing a known
     *       tribe name. This is the exact T4.6 Legends {@code .playerDetails}
     *       shape and the most precise match.</li>
     *   <li><b>CSS class</b> — {@code tribeN}/{@code gN} id suffixes used on
     *       tribe-icon elements. Language-agnostic.</li>
     *   <li><b>Image URL</b> — {@code /tribes/N.png} asset path.
     *       Language-agnostic.</li>
     *   <li><b>English text</b> — whole-scope scan for "Tribe … Egyptians".
     *       Last-resort fallback.</li>
     * </ol>
     *
     * Scope narrowing includes {@code .playerDetails} (heroV2 skin) and the
     * older {@code #details}/{@code .details} variants before falling back to
     * the whole document. We never return null until all four passes have
     * been exhausted.
     */
    static Tribe parseTribe(String html) {
        if (html == null || html.isBlank()) return null;
        Document doc = Jsoup.parse(html);

        // Narrow the scope to the profile / details region so menu and sidebar
        // content doesn't bias the scan. {@code .playerDetails} is the heroV2
        // container on T4.6 Legends; {@code #details}/{@code .details} covers
        // older skins. Falls back to the whole document.
        Element scope = firstNonNull(
                doc.selectFirst(".playerDetails"),
                doc.selectFirst("#details"),
                doc.selectFirst(".details"),
                doc.selectFirst("#content"),
                doc.body());
        if (scope == null) scope = doc; // headless/malformed HTML guard

        // Pass 0 — structural <th>Tribe</th><td>Name</td> pairing. This is the
        // highest-confidence hit on T4.6's English Legends skin: we know the
        // row shape exactly, no proximity heuristics needed.
        Tribe fromRow = findTribeByLabeledRow(scope);
        if (fromRow != null) return fromRow;

        // Pass 1 — CSS class. Most reliable cross-locale signal when it's
        // present (older tribe-icon skins). heroV2 profiles usually don't
        // include it, which is why Pass 0 has to exist.
        Tribe fromClass = findTribeClass(scope);
        if (fromClass != null) return fromClass;

        // Pass 2 — image URL. Some skins only surface the tribe through the
        // image asset path (e.g. /img/u/tribes/3.png). Also language-agnostic.
        Tribe fromImage = findTribeImage(scope);
        if (fromImage != null) return fromImage;

        // Pass 3 — English "Tribe …" text. Useful on English skins where the
        // row shape is unusual; narrow window guards against picking up a
        // different tribe mentioned elsewhere in the scope.
        Tribe fromText = findTribeByEnglishLabel(scope.text());
        if (fromText != null) return fromText;

        // Final fallback: retry Pass 3 against the WHOLE document text. If
        // scope narrowing missed the row (e.g. novel Travian skin), this
        // catches the "Tribe Egyptians" fragment wherever it lives.
        return findTribeByEnglishLabel(doc.text());
    }

    /**
     * Pass 0 — structural row lookup. Walks every {@code <tr>} (and a few
     * {@code dl}-style alternatives) looking for a label element whose text
     * is exactly "Tribe" (case-insensitive, trimming punctuation) and reads
     * the value from the next sibling cell.
     * <p>
     * This is the only pass that can match T4.6 Legends' heroV2 profile,
     * which does not include a tribe-id class or a tribe image URL.
     */
    private static Tribe findTribeByLabeledRow(Element scope) {
        for (Element th : scope.select("tr th, tr td, dt, dl > *")) {
            String label = th.text();
            if (label == null) continue;
            String normalized = label.trim().toLowerCase(Locale.ROOT).replaceAll("[:\\s]+$", "");
            if (!normalized.equals("tribe")) continue;
            Element value = th.nextElementSibling();
            if (value == null) continue;
            Tribe t = matchTribeWord(value.text());
            if (t != null) return t;
        }
        return null;
    }

    /**
     * Pass 1: find a class attribute containing a tribe id suffix. Iterates
     * elements with a {@code class} attribute; short-circuits on the first
     * usable hit.
     */
    private static Tribe findTribeClass(Element scope) {
        for (Element el : scope.select("[class]")) {
            String klass = el.className();
            if (klass.isEmpty()) continue;
            Matcher m = TRIBE_CLASS_PATTERN.matcher(klass);
            while (m.find()) {
                Tribe t = tribeFromId(m.group(1));
                if (t != null) return t;
                // else: matched tribe4 (Nature) or tribe5 (Natars) — keep looking
                // for a real tribe in the same class attr (unlikely but cheap).
            }
        }
        return null;
    }

    /**
     * Pass 2: scan {@code <img src=…>} attributes for a tribe-image URL. We
     * look only inside the already-narrowed scope to avoid the ubiquitous
     * tribe icons in top navigation menus.
     */
    private static Tribe findTribeImage(Element scope) {
        for (Element img : scope.select("img[src]")) {
            String src = img.attr("src");
            if (src.isEmpty()) continue;
            Matcher m = TRIBE_IMAGE_URL_PATTERN.matcher(src);
            if (m.find()) {
                Tribe t = tribeFromId(m.group(1));
                if (t != null) return t;
            }
        }
        return null;
    }

    /**
     * Pass 3: pure-text scan for the English word "Tribe" followed closely by
     * a tribe name. Last-resort only — the first two passes cover localized
     * skins, this one rescues English skins that fail the HTML structure
     * checks.
     */
    private static Tribe findTribeByEnglishLabel(String text) {
        if (text == null || text.isBlank()) return null;
        String lowered = text.toLowerCase(Locale.ROOT);
        int idx = lowered.indexOf("tribe");
        while (idx >= 0) {
            int end = Math.min(lowered.length(), idx + 40);
            Tribe t = matchTribeWord(lowered.substring(idx, end));
            if (t != null) return t;
            idx = lowered.indexOf("tribe", idx + 1);
        }
        return null;
    }

    /**
     * Resolve a Travian internal tribe id to our {@link Tribe} enum. Returns
     * {@code null} for NPC ids (4=Nature, 5=Natars) and anything outside the
     * known 1..9 range — callers treat that as "keep looking".
     */
    private static Tribe tribeFromId(String raw) {
        try {
            int id = Integer.parseInt(raw);
            return switch (id) {
                case 1 -> Tribe.ROMANS;
                case 2 -> Tribe.TEUTONS;
                case 3 -> Tribe.GAULS;
                case 6 -> Tribe.EGYPTIANS;
                case 7 -> Tribe.HUNS;
                case 8 -> Tribe.SPARTANS;
                case 9 -> Tribe.VIKINGS;
                default -> null; // 4 (Nature) and 5 (Natars) are NPCs
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Tribe matchTribeWord(String text) {
        String lowered = text.toLowerCase(Locale.ROOT);
        // Match the English singular/plural forms Travian uses.
        if (lowered.contains("gauls") || lowered.contains("gaul")) return Tribe.GAULS;
        if (lowered.contains("romans") || lowered.contains("roman")) return Tribe.ROMANS;
        if (lowered.contains("teutons") || lowered.contains("teuton")) return Tribe.TEUTONS;
        if (lowered.contains("egyptians") || lowered.contains("egyptian")) return Tribe.EGYPTIANS;
        if (lowered.contains("huns") || lowered.contains("hun")) return Tribe.HUNS;
        if (lowered.contains("spartans") || lowered.contains("spartan")) return Tribe.SPARTANS;
        if (lowered.contains("vikings") || lowered.contains("viking")) return Tribe.VIKINGS;
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T c : candidates) if (c != null) return c;
        return null;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
