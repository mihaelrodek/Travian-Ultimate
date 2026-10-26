package com.travianbot.server.discovery;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.server.domain.Server;
import com.travianbot.server.domain.UpcomingServer;
import com.travianbot.server.persistence.UpcomingServerRepository;
import com.travianbot.server.service.ServerService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes {@code https://www.travian.com/international/calendar} for upcoming
 * world launches and upserts each parsed entry as an {@link UpcomingServer}.
 *
 * <h3>Strategy</h3>
 * The page is a React SPA that returns only a shell over plain HTTP, but the
 * calendar itself is rendered <strong>without</strong> a login wall. Playwright
 * (headless) loads the page, we grab the hydrated HTML via {@code page.content()},
 * then parse it deterministically with Jsoup using the actual class structure
 * Travian renders:
 *
 * <pre>
 * &lt;main id="gameworldCalendar"&gt;
 *   &lt;h2&gt;June 2026&lt;/h2&gt;
 *   &lt;div class="calendarGameworld gameworldDetails"&gt;
 *       &lt;div class="day [active]"&gt;
 *           &lt;div class="weekday"&gt;Thu&lt;/div&gt;&lt;div&gt;18&lt;/div&gt;
 *       &lt;/div&gt;
 *       &lt;div class="title tag normal|special"&gt;
 *           &lt;div class="text"&gt;&lt;span class="name"&gt;Europe 5&lt;/span&gt;&lt;/div&gt;
 *       &lt;/div&gt;
 *       &lt;div class="speed"&gt;&lt;i class="speed"/&gt;×1&lt;/div&gt;
 *       &lt;div class="tribes"&gt;&lt;div class="tribeImages"&gt;
 *           &lt;i class="tribe roman"/&gt;…&lt;i class="tribe spartan"/&gt;
 *       &lt;/div&gt;&lt;/div&gt;
 *       &lt;div class="startTime"&gt;&lt;i/&gt;&lt;div&gt;15:00&amp;nbsp;&lt;span&gt;(UTC+1)&lt;/span&gt;&lt;/div&gt;&lt;/div&gt;
 *       &lt;div class="artefacts"&gt;&lt;i/&gt;16/09&lt;/div&gt;
 *       &lt;div class="constructionPlans"&gt;&lt;i/&gt;15/12&lt;/div&gt;
 *       &lt;div class="endCondition"&gt;&lt;i class="ww"/&gt;&lt;span&gt;100&lt;/span&gt;&lt;/div&gt;
 *   &lt;/div&gt;
 *   …more entries…
 * &lt;/main&gt;
 * </pre>
 *
 * <p>The previous heuristic selectors are gone — we now have the real DOM.
 * The parser is split out as a static method so the unit test can feed it a
 * fixture HTML without a live browser.
 *
 * <p><strong>Times</strong> on the calendar are quoted in {@code UTC+1}. We
 * treat that as a fixed offset regardless of CET vs CEST — Travian publishes
 * all start times as UTC+1 year-round.
 *
 * <p><strong>Inferred server URL</strong>: once we know the world name
 * ("Europe 5", "Arabics 50", "Europe 100") and the speed multiplier, we can
 * infer the eventual server URL using the same scheme our discovery probe
 * generates — {@code ts{N}.x{SPEED}.{community}.travian.com}. Stored so the
 * UI can link straight to the gameworld once it opens.
 */
@Service
@Transactional
public class CalendarScraperService {

    private static final Logger log = LoggerFactory.getLogger(CalendarScraperService.class);

    private static final String CALENDAR_URL = "https://www.travian.com/international/calendar";
    private static final int NAVIGATION_TIMEOUT_MS = 30_000;
    private static final int HYDRATION_WAIT_MS = 20_000;
    /** How much HTML to dump on a zero-parse failure. */
    private static final int HTML_DUMP_MAX_CHARS = 3000;

    /** Travian's wall clock — UTC+1 year-round on the lobby. */
    private static final ZoneOffset TRAVIAN_LOBBY_OFFSET = ZoneOffset.ofHours(1);

    /** Communities we know how to map into the URL scheme. Lowercase keys. */
    private static final List<String> KNOWN_COMMUNITIES = List.of(
            "international", "europe", "america", "asia", "arabics", "czsk"
    );

    /** Pattern matching the speed cell text, e.g. "×1", "×10". */
    private static final Pattern SPEED_PATTERN = Pattern.compile("[\\u00d7x](\\d{1,3})", Pattern.CASE_INSENSITIVE);

    /** "15:00&nbsp;(UTC+1)" — relaxed to allow either real space or NBSP. */
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");

    /** Month-year header like "June 2026" / "July 2026". */
    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final UpcomingServerRepository repository;
    private final ServerService serverService;
    @SuppressWarnings("unused")
    private final BotEventService botEventService;

    public CalendarScraperService(UpcomingServerRepository repository,
                                  ServerService serverService,
                                  BotEventService botEventService) {
        this.repository = repository;
        this.serverService = serverService;
        this.botEventService = botEventService;
    }

    // ------------------------------------------------------------------------
    // Public entry point — Playwright-driven scrape.
    // ------------------------------------------------------------------------

    public CalendarScrapeReport scrape() {
        long startNs = System.nanoTime();
        log.info("CalendarScraper: starting scrape of {}", CALENDAR_URL);

        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            context = browser.newContext();
            page = context.newPage();
            page.setDefaultTimeout(NAVIGATION_TIMEOUT_MS);

            page.navigate(CALENDAR_URL, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            } catch (PlaywrightException ignored) {
                // navigation races are fine — the hydration wait below is the real gate
            }

            // Wait for the actual calendar root to render. If it doesn't appear we still
            // proceed — the parser will emit zero entries and the WARN dump below kicks in.
            try {
                page.waitForSelector("#gameworldCalendar .calendarGameworld", new Page.WaitForSelectorOptions()
                        .setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(HYDRATION_WAIT_MS));
            } catch (PlaywrightException ignored) {
                log.debug("CalendarScraper: hydration selector did not match within {}ms — proceeding anyway",
                        HYDRATION_WAIT_MS);
            }

            String html = page.content();
            List<ParsedEntry> entries = parseCalendarHtml(html);

            if (entries.isEmpty()) {
                String sample = html == null ? "(null)" :
                        html.length() > HTML_DUMP_MAX_CHARS
                                ? html.substring(0, HTML_DUMP_MAX_CHARS) + "…[truncated]" : html;
                log.warn("CalendarScraper: parsed ZERO entries — Travian DOM may have changed. " +
                        "First {} chars of HTML follow:\n{}", HTML_DUMP_MAX_CHARS, sample);
            }

            int created = 0;
            int updated = 0;
            int linkedToServer = 0;
            for (ParsedEntry e : entries) {
                // Pre-register the inferred Server row (active=false) so map.sql
                // sync is ready to flip on the day the world goes live. Specials
                // and rows without a known TS number end up with a null
                // inferredServerUrl and skip this branch.
                Long linkedServerId = null;
                if (e.inferredServerUrl != null) {
                    try {
                        Server preregistered = serverService.getOrCreateInactive(
                                e.inferredServerUrl,
                                e.worldName,
                                e.region,
                                e.speed == null ? null : "x" + e.speed);
                        linkedServerId = preregistered.getId();
                        linkedToServer++;
                    } catch (RuntimeException ex) {
                        // Never let a Server upsert hiccup abort the calendar
                        // scrape — log and continue with the upcoming row only.
                        log.warn("CalendarScraper: could not pre-register server for {}: {}",
                                e.inferredServerUrl, ex.getMessage());
                    }
                }

                Optional<UpcomingServer> existing = repository.findFirstByCalendarId(e.calendarId);
                if (existing.isPresent()) {
                    UpcomingServer row = existing.get();
                    row.setRegion(e.region);
                    row.setWorldName(e.worldName);
                    row.setSpeed(e.speed);
                    if (e.scheduledStartAt != null) row.setScheduledStartAt(e.scheduledStartAt);
                    row.setStatus(e.status);
                    row.setSourceUrl(CALENDAR_URL);
                    if (e.inferredServerUrl != null) row.setInferredServerUrl(e.inferredServerUrl);
                    if (linkedServerId != null) row.setLinkedServerId(linkedServerId);
                    row.setRawJson(e.rawJson);
                    row.setLastSeenAt(Instant.now());
                    repository.save(row);
                    updated++;
                } else {
                    UpcomingServer row = new UpcomingServer(e.calendarId, e.region, e.worldName, e.status);
                    row.setSpeed(e.speed);
                    row.setScheduledStartAt(e.scheduledStartAt);
                    row.setSourceUrl(CALENDAR_URL);
                    row.setInferredServerUrl(e.inferredServerUrl);
                    row.setLinkedServerId(linkedServerId);
                    row.setRawJson(e.rawJson);
                    repository.save(row);
                    created++;
                }
            }
            log.info("CalendarScraper: pre-registered {} server URL(s) from calendar entries", linkedToServer);

            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.info("CalendarScraper: parsed={} created={} updated={} durationMs={}",
                    entries.size(), created, updated, durationMs);
            return new CalendarScrapeReport(entries.size(), created, updated, durationMs);
        } catch (PlaywrightException e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            log.warn("CalendarScraper: Playwright failure after {}ms: {}", durationMs, e.getMessage());
            return new CalendarScrapeReport(0, 0, 0, durationMs);
        } finally {
            closeQuietly("page", page == null ? null : page::close);
            closeQuietly("context", context == null ? null : context::close);
            closeQuietly("browser", browser == null ? null : browser::close);
            closeQuietly("playwright", playwright == null ? null : playwright::close);
        }
    }

    // ------------------------------------------------------------------------
    // Pure parser — Jsoup against the rendered HTML. Static + side-effect-free
    // so it can be unit-tested with a fixture.
    // ------------------------------------------------------------------------

    /**
     * Parse the rendered calendar HTML into a list of upcoming-server entries.
     * <p>
     * Walks {@code #gameworldCalendar} in document order, tracking the current
     * month/year context from the {@code <h2>} headings (e.g. "June 2026") and
     * binding each subsequent {@code .calendarGameworld} block to it.
     */
    public static List<ParsedEntry> parseCalendarHtml(String html) {
        if (html == null || html.isBlank()) return List.of();
        Document doc = Jsoup.parse(html);
        Element root = doc.selectFirst("#gameworldCalendar");
        if (root == null) return List.of();

        // Children in DOM order: <h1>, <h2>, .calendarGameworld, <h2>, .calendarGameworld, …
        Map<String, ParsedEntry> dedup = new LinkedHashMap<>();
        Month currentMonth = null;
        int currentYear = -1;
        Instant now = Instant.now();

        for (Element child : root.children()) {
            if (child.tagName().equalsIgnoreCase("h2")) {
                Header parsed = parseMonthYear(child.text());
                if (parsed != null) {
                    currentMonth = parsed.month;
                    currentYear = parsed.year;
                }
                continue;
            }
            if (!child.hasClass("calendarGameworld")) continue;
            if (currentMonth == null) continue; // no header yet — skip

            ParsedEntry entry = parseEntry(child, currentMonth, currentYear, now);
            if (entry == null || entry.calendarId == null) continue;
            dedup.putIfAbsent(entry.calendarId, entry);
        }
        return new ArrayList<>(dedup.values());
    }

    /** Parse one {@code .calendarGameworld} block. Returns null on malformed input. */
    private static ParsedEntry parseEntry(Element block, Month month, int year, Instant now) {
        ParsedEntry e = new ParsedEntry();

        // ---- day number ----
        Element dayCell = block.selectFirst(".day");
        Integer dayOfMonth = null;
        boolean active = false;
        if (dayCell != null) {
            active = dayCell.classNames().contains("active");
            for (Element d : dayCell.children()) {
                if (d.hasClass("weekday")) continue;
                Integer parsed = tryParseInt(d.text());
                if (parsed != null) { dayOfMonth = parsed; break; }
            }
        }

        // ---- world name + special flag + subtitle ----
        Element title = block.selectFirst(".title");
        String worldName = null;
        String subtitle = null;
        boolean special = false;
        if (title != null) {
            special = title.classNames().contains("special");
            Element name = title.selectFirst(".name");
            if (name != null) worldName = name.text().trim();
            Element sub = title.selectFirst(".subtitle");
            if (sub != null) subtitle = sub.text().trim();
        }
        if (worldName == null || worldName.isEmpty()) return null;
        e.worldName = subtitle == null || subtitle.isBlank()
                ? worldName
                : worldName + " — " + subtitle;
        e.special = special;

        // ---- speed ----
        Element speedCell = block.selectFirst(".speed");
        Integer speed = null;
        if (speedCell != null) {
            Matcher m = SPEED_PATTERN.matcher(speedCell.text());
            if (m.find()) speed = tryParseInt(m.group(1));
        }
        e.speed = speed;

        // ---- tribes ----
        Elements tribeNodes = block.select(".tribes i.tribe");
        List<String> tribes = new ArrayList<>(tribeNodes.size());
        for (Element t : tribeNodes) {
            for (String cls : t.classNames()) {
                if ("tribe".equals(cls)) continue;
                tribes.add(cls);
            }
        }
        e.tribes = tribes;

        // ---- start time ----
        LocalTime startTime = null;
        Element startCell = block.selectFirst(".startTime");
        if (startCell != null) {
            Matcher m = TIME_PATTERN.matcher(startCell.text());
            if (m.find()) {
                try {
                    int hh = Integer.parseInt(m.group(1));
                    int mm = Integer.parseInt(m.group(2));
                    startTime = LocalTime.of(hh, mm);
                } catch (Exception ignored) {
                    // leave null
                }
            }
        }

        // ---- combine date + time ----
        if (dayOfMonth != null) {
            try {
                LocalDate date = LocalDate.of(year, month, dayOfMonth);
                LocalDateTime ldt = date.atTime(startTime != null ? startTime : LocalTime.of(0, 0));
                e.scheduledStartAt = ldt.atOffset(TRAVIAN_LOBBY_OFFSET).toInstant();
            } catch (Exception ignored) {
                // invalid date (e.g. 31 February) — leave null
            }
        }

        // ---- artefacts / construction-plans / end-condition (informational extras) ----
        e.artefactsDate = textAfterIcon(block.selectFirst(".artefacts"));
        e.constructionPlansDate = textAfterIcon(block.selectFirst(".constructionPlans"));
        Element endSpan = block.selectFirst(".endCondition span");
        if (endSpan != null) e.endConditionVillages = tryParseInt(endSpan.text());

        // ---- region (community) from the world name ----
        // For special events (Travian Schild, Community Week, …) the world
        // name has no continent prefix — fall back to "Special" so the
        // non-null column accepts it and the UI can group these neatly.
        String inferredRegion = inferCommunityFromName(worldName);
        e.region = inferredRegion != null ? inferredRegion : "Special";

        // ---- status ----
        e.status = deriveStatus(e.scheduledStartAt, active, now);

        // ---- inferred server URL ----
        e.inferredServerUrl = inferServerUrl(worldName, speed, e.region, special);

        // ---- calendarId ----
        e.calendarId = buildCalendarId(e.region, e.worldName, e.scheduledStartAt);

        // ---- rawJson (truncated dump for debugging) ----
        e.rawJson = block.text().trim();
        if (e.rawJson.length() > 4000) e.rawJson = e.rawJson.substring(0, 4000);

        return e;
    }

    /**
     * Pull the trailing text of a cell like {@code <div class="artefacts"><i/>16/09</div>}
     * by removing any {@code <i>} children and trimming. Returns null when the
     * cell is absent or its non-icon text is empty / just "?".
     */
    private static String textAfterIcon(Element cell) {
        if (cell == null) return null;
        Element clone = cell.clone();
        clone.select("i").remove();
        String text = clone.text().trim();
        if (text.isEmpty() || "?".equals(text)) return null;
        return text;
    }

    /**
     * Map a world name like "Europe 5" / "Arabics 50" / "ARABICS 9" /
     * "International 31" to its lowercase community code. Returns null for
     * special events ("Travian Schild", "Community Week x5"…) — those have no
     * matching continent.
     */
    static String inferCommunityFromName(String worldName) {
        if (worldName == null) return null;
        String[] parts = worldName.trim().split("\\s+");
        if (parts.length == 0) return null;
        String head = parts[0].toLowerCase(Locale.ENGLISH);
        for (String community : KNOWN_COMMUNITIES) {
            if (community.equals(head)) return capitalise(community);
        }
        return null;
    }

    /**
     * Infer the eventual URL of the world from its name + speed. Returns null
     * for specials (no community match) or when we can't extract a TS number.
     * Format: {@code https://ts{N}.x{SPEED}.{community}.travian.com}.
     */
    static String inferServerUrl(String worldName, Integer speed, String region, boolean special) {
        if (special || worldName == null || speed == null || region == null) return null;
        Integer tsNumber = extractTrailingNumber(worldName);
        if (tsNumber == null) return null;
        String community = region.toLowerCase(Locale.ENGLISH);
        return "https://ts" + tsNumber + ".x" + speed + "." + community + ".travian.com";
    }

    /** Extract the trailing integer from a string like "Europe 100" → 100. */
    static Integer extractTrailingNumber(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("(\\d{1,4})\\s*$").matcher(s.trim());
        if (m.find()) return tryParseInt(m.group(1));
        return null;
    }

    /**
     * Derive a status from the start timestamp + the {@code active} CSS class
     * Travian renders on rows whose start time has passed.
     * <ul>
     *   <li>{@code active && startAt is in the past} → {@code RUNNING}</li>
     *   <li>{@code active && startAt is in the future or unknown} → {@code OPEN_REGISTRATION}</li>
     *   <li>{@code !active && startAt is in the future} → {@code ANNOUNCED}</li>
     *   <li>otherwise → {@code UNKNOWN}</li>
     * </ul>
     */
    private static String deriveStatus(Instant scheduledStartAt, boolean active, Instant now) {
        if (active) {
            if (scheduledStartAt != null && scheduledStartAt.isBefore(now)) return "RUNNING";
            return "OPEN_REGISTRATION";
        }
        if (scheduledStartAt != null && scheduledStartAt.isAfter(now)) return "ANNOUNCED";
        return "UNKNOWN";
    }

    /**
     * Synthesise a stable id from the row's natural key. Region + world-name +
     * start-date is unique-enough for the calendar.
     */
    private static String buildCalendarId(String region, String worldName, Instant scheduledStartAt) {
        if (worldName == null) return null;
        String regionPart = region == null ? "special" : slug(region);
        String worldPart = slug(worldName);
        String datePart = scheduledStartAt == null
                ? "tbd"
                : DateTimeFormatter.ISO_LOCAL_DATE.format(scheduledStartAt.atZone(ZoneOffset.UTC).toLocalDate());
        String id = regionPart + ":" + worldPart + ":" + datePart;
        return id.length() > 200 ? id.substring(0, 200) : id;
    }

    /** Parse "June 2026" → (Month.JUNE, 2026). Returns null on garbage. */
    private static Header parseMonthYear(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        try {
            LocalDate parsed = LocalDate.parse("1 " + trimmed,
                    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
            return new Header(parsed.getMonth(), parsed.getYear());
        } catch (Exception ignored) {
            // fallback: try the formatter without prepending day-1
            try {
                LocalDate parsed = LocalDate.parse("01 " + trimmed,
                        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
                return new Header(parsed.getMonth(), parsed.getYear());
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private static String slug(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ENGLISH);
        StringBuilder sb = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c == ' ' || c == '-' || c == '_' || c == '/') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Integer tryParseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void closeQuietly(String what, ThrowingRunnable r) {
        if (r == null) return;
        try { r.run(); }
        catch (Exception e) { log.debug("CalendarScraper: {} close() failed: {}", what, e.getMessage()); }
    }

    private record Header(Month month, int year) {}

    /**
     * Mutable parse result. Public-but-final so the unit test can inspect every
     * field without us having to expose accessors one-by-one.
     */
    public static final class ParsedEntry {
        public String calendarId;
        public String region;
        public String worldName;
        public Integer speed;
        public List<String> tribes;
        public boolean special;
        public Instant scheduledStartAt;
        public String status;
        public String inferredServerUrl;
        public String artefactsDate;        // e.g. "16/09" (DD/MM)
        public String constructionPlansDate;
        public Integer endConditionVillages; // e.g. 100
        public String rawJson;
    }
}
