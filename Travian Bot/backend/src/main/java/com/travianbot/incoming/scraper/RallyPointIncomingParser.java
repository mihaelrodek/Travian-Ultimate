package com.travianbot.incoming.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the rally-point "Incoming attacks" page ({@code /build.php?gid=16&tt=1})
 * into a list of {@link ParsedIncomingAttack} records.
 * <p>
 * Why tolerant selectors? T4.6 Legends ships at least three skins that style
 * the rally-point page slightly differently:
 * <ul>
 *   <li>Modern skins wrap each incoming wave in a {@code <table class="troop_details inAttack">}
 *       (raids are {@code .inRaid}). Each row carries a {@code .troopHeadline}
 *       with the attacker link and an arrival timer in {@code span.timer[value="X"]}.</li>
 *   <li>Older skins drop the wrapper class — the timer + headline are still
 *       there but the table looks plain. We fall back to scanning every
 *       {@code table.troop_details} and inspecting class names for the
 *       attack/raid hint.</li>
 *   <li>Some skins (the dark-mode community theme) move the alliance tag into
 *       {@code a.allyTag} sitting next to the attacker link instead of inside
 *       a dedicated cell.</li>
 * </ul>
 * <p>
 * The parser must never throw on a missing field — a half-parseable wave is
 * still useful (we'll show "unknown attacker" and skip the planner
 * suggestion). Anything we can't interpret stays null on the returned record.
 * <p>
 * {@code now} is taken as a parameter so the relative timer value
 * ({@code seconds-until-arrival}) can be converted to an absolute Instant
 * deterministically — critical for tests, and for keeping arrivals stable
 * across the few ms between {@code page.content()} and the row write.
 */
public final class RallyPointIncomingParser {

    private static final Logger log = LoggerFactory.getLogger(RallyPointIncomingParser.class);

    /** Pulls "x=12&y=-34" or "?x=12&y=-34" out of a {@code karte.php?x=&y=} href. */
    private static final Pattern COORD_HREF = Pattern.compile(
            "[?&]x=(-?\\d{1,4})\\s*&\\s*y=(-?\\d{1,4})"
    );

    /** Fallback: "(12|-34)" / "(12,-34)" anywhere in headline text. */
    private static final Pattern COORD_TEXT = Pattern.compile(
            "\\(\\s*(-?\\d{1,4})\\s*[|,/]\\s*(-?\\d{1,4})\\s*\\)"
    );

    /** Match digits only — {@code value="123"} on the countdown timer. */
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private RallyPointIncomingParser() {
    }

    /**
     * Unified entry point for paste-in import. Detects whether the payload
     * looks like rally-point HTML (tables / class attributes) and dispatches
     * to {@link #parse} or {@link #parsePlainText} accordingly.
     * <p>
     * Browsers paste plain text into a {@code <textarea>} by default, so the
     * plain-text path is the common case here. The HTML path is bonus
     * coverage for users who paste view-source output.
     */
    public static List<ParsedIncomingAttack> parsePastedContent(String body, Instant now) {
        if (body == null || body.isBlank()) return List.of();
        String cleaned = stripBidiMarks(body);
        if (looksLikeRallyHtml(cleaned)) return parse(cleaned, now);
        return parsePlainText(cleaned, now);
    }

    /**
     * Strip unicode bidirectional-formatting controls that browsers inject
     * around signed numbers so a minus shows on the correct side
     * (e.g. {@code (−104|54)} actually contains U+202D / U+202C wrapping each
     * digit run). Ctrl+C carries the marks with the text and they sit
     * <em>between</em> the opening paren and the digits, breaking every
     * coordinate regex on this page.
     * <p>
     * Removed: LRM/RLM (U+200E, U+200F), LRE/RLE/PDF/LRO/RLO (U+202A–U+202E),
     * LRI/RLI/FSI/PDI isolates (U+2066–U+2069), and BOM (U+FEFF).
     */
    public static String stripBidiMarks(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]", "");
    }

    static boolean looksLikeRallyHtml(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return lower.contains("<table")
                || lower.contains("troop_details")
                || lower.contains("class=\"")
                || lower.contains("<tr");
    }

    /**
     * @param html the raw HTML of {@code /build.php?gid=16&tt=1}
     * @param now  the wall-clock instant to anchor relative timers against
     */
    public static List<ParsedIncomingAttack> parse(String html, Instant now) {
        String cleaned = stripBidiMarks(html);
        // Same server-time anchor as the plain-text path. For live-scrape
        // calls the latency is in the hundreds of ms and this is a no-op;
        // for view-source paste-ins it shields against copy/paste delay.
        Instant reference = resolveServerNow(cleaned, now);
        Document doc = Jsoup.parse(cleaned);
        List<ParsedIncomingAttack> out = new ArrayList<>();

        // Try the explicit-class selectors first. If neither hit, fall back
        // to every troop_details table on the page — class might have drifted.
        Elements waves = doc.select("table.troop_details.inAttack, table.troop_details.inRaid");
        if (waves.isEmpty()) {
            waves = doc.select("table.troop_details");
        }
        if (waves.isEmpty()) {
            // Very old skin / non-rally-point page — nothing to parse.
            log.debug("Rally-point parser: no .troop_details tables on page");
            return out;
        }

        for (Element wave : waves) {
            ParsedIncomingAttack parsed = parseWave(wave, reference);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    /**
     * Parses a single {@code <table class="troop_details ...">} block. Returns
     * null if the table doesn't have enough signal to be considered an
     * incoming wave (e.g. it's actually an outgoing-attack block on a
     * different rally-point tab that someone navigated to by accident).
     */
    private static ParsedIncomingAttack parseWave(Element wave, Instant now) {
        // ---- attack vs raid ----
        // Modern skins put the hint on the table class; older skins on a
        // child .role / .attackType element. We look at both.
        String waveClasses = wave.className() == null ? "" : wave.className().toLowerCase();
        boolean isAttack;
        if (waveClasses.contains("inattack")) {
            isAttack = true;
        } else if (waveClasses.contains("inraid")) {
            isAttack = false;
        } else {
            // Fall back to text inspection of the role / type cell.
            Element role = wave.selectFirst(".role, .attackType, .attack_type");
            String roleText = role == null ? wave.text() : role.text();
            // Heuristic — words "raid" or German "Beutezug" → raid; default attack.
            String lower = roleText == null ? "" : roleText.toLowerCase();
            isAttack = !(lower.contains("raid") || lower.contains("beutezug"));
        }

        // ---- arrival time ----
        // The countdown timer is a <span class="timer" value="<seconds-left>">.
        // Some skins use data-end attributes; we accept either.
        Instant arrivalAt = parseArrivalAt(wave, now);
        if (arrivalAt == null) {
            // No timer at all — this isn't a usable wave row.
            log.debug("Rally-point parser: skipping wave with no parseable timer");
            return null;
        }

        // ---- attacker name + alliance ----
        String attackerName = textOrNull(wave.selectFirst(
                ".troopHeadline a.attacker, "
                        + ".troopHeadline .attacker, "
                        + "a.attacker"
        ));

        String alliance = textOrNull(wave.selectFirst("a.allyTag, .allyTag, .ally"));

        // ---- source village name + coords ----
        // The source-village link looks like <a href="karte.php?x=12&y=-34">VillageName</a>.
        // We try a couple of common locations inside the headline.
        String sourceVillageName = null;
        Integer sourceX = null;
        Integer sourceY = null;

        Element coordLink = wave.selectFirst(
                ".troopHeadline a[href*=\"karte.php\"], "
                        + ".troopHeadline a[href*=\"x=\"], "
                        + "a[href*=\"karte.php\"]"
        );
        if (coordLink != null) {
            String href = coordLink.attr("href");
            int[] xy = parseCoordsFromHref(href);
            if (xy != null) {
                sourceX = xy[0];
                sourceY = xy[1];
            }
            String linkText = coordLink.text();
            if (linkText != null && !linkText.isBlank()) {
                sourceVillageName = linkText.trim();
            }
        }

        // Fallback: look for "(12|-34)" anywhere in the headline text.
        if (sourceX == null || sourceY == null) {
            Element headline = wave.selectFirst(".troopHeadline");
            String headlineText = headline == null ? wave.text() : headline.text();
            if (headlineText != null) {
                Matcher m = COORD_TEXT.matcher(headlineText);
                if (m.find()) {
                    sourceX = parseIntOrNull(m.group(1));
                    sourceY = parseIntOrNull(m.group(2));
                }
            }
        }

        // ---- wave count ----
        // Modern skins render one row per wave inside a tbody — count those.
        // Older skins lump several waves into a single table with .rowTroopsCount
        // headers. We probe both.
        int waveCount = 1;
        Elements countRows = wave.select("tr.rowTroopsCount, tr.troopCount");
        if (!countRows.isEmpty()) {
            waveCount = countRows.size();
        }

        return new ParsedIncomingAttack(
                sourceX,
                sourceY,
                sourceVillageName,
                attackerName,
                alliance,
                arrivalAt,
                waveCount,
                isAttack
        );
    }

    private static Instant parseArrivalAt(Element wave, Instant now) {
        Element timer = wave.selectFirst(
                "td.in span.timer, span.timer, .timer, [data-end]"
        );
        if (timer == null) return null;
        String raw = timer.attr("value");
        if (raw == null || raw.isBlank()) raw = timer.attr("data-end");
        if (raw == null || raw.isBlank()) raw = timer.text();
        if (raw == null) return null;

        Matcher m = DIGITS.matcher(raw);
        if (!m.find()) return null;
        try {
            long secs = Long.parseLong(m.group(1));
            if (secs <= 0) return null;
            return now.plusSeconds(secs);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int[] parseCoordsFromHref(String href) {
        if (href == null || href.isBlank()) return null;
        Matcher m = COORD_HREF.matcher(href);
        if (!m.find()) return null;
        Integer x = parseIntOrNull(m.group(1));
        Integer y = parseIntOrNull(m.group(2));
        if (x == null || y == null) return null;
        return new int[] { x, y };
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOrNull(Element el) {
        if (el == null) return null;
        String t = el.text();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    // -- Plain-text fallback ------------------------------------------------

    /**
     * Parenthesised coordinate, e.g. {@code (-104|54)}. In a rally-point
     * Overview paste this is ALWAYS the defender's village (the user's own
     * village receiving the attack). Used as a fallback source only when the
     * unparenthesised {@link #BARE_COORD} pattern below is missing.
     */
    private static final Pattern PLAIN_COORD = Pattern.compile(
            "\\(\\s*([\\u2212+-]?\\d{1,4})\\s*[|,/]\\s*([\\u2212+-]?\\d{1,4})\\s*\\)"
    );

    /**
     * Bare (unparenthesised) coordinate, e.g. {@code Natars -78|150}. In the
     * Overview paste this is the ATTACKER'S source village. We use negative
     * lookarounds so it can't double-match a parenthesised coord — we want
     * each paren form to live solely in {@link #PLAIN_COORD}.
     */
    private static final Pattern BARE_COORD = Pattern.compile(
            "(?<![\\d()|])([\\u2212+-]?\\d{1,4})\\s*\\|\\s*([\\u2212+-]?\\d{1,4})(?![\\d|)])"
    );

    /**
     * Plain-text duration: "H:MM:SS" or "H:MM". Anchored on word boundaries
     * so we don't grab plain numbers. Rally-point overview shows arrivals as
     * relative durations ("in 1:23:45") — that's what we parse here.
     */
    private static final Pattern PLAIN_DURATION = Pattern.compile(
            "\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\b"
    );

    /**
     * Anchor for one wave block in the Overview paste. Travian renders each
     * incoming wave with a line of the form {@code "Arrival\tin H:MM:SS"};
     * we use this as the only structurally reliable per-wave landmark and
     * walk backwards from each occurrence to assemble the rest.
     */
    private static final Pattern ARRIVAL_ANCHOR = Pattern.compile(
            "(?im)^[^\\S\\n]*Arrival\\s+in\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?)"
    );

    /**
     * "Server time: HH:MM:SS (UTC +01:00)" header that Travian renders at the
     * top of every page. We use this as the reference {@code now} when
     * importing a paste so the "arrival = now + duration" computation is
     * anchored to <em>when the page was rendered</em>, not when the user got
     * around to pasting it. Without this anchor, a 5-minute pause between
     * Ctrl+C and Ctrl+V shifts every arrival 5 minutes later in our table.
     * <p>
     * Capture groups:
     * <ol>
     *   <li>HH (1–2 digits)</li>
     *   <li>MM (2 digits)</li>
     *   <li>SS (2 digits)</li>
     *   <li>Sign of TZ offset ('+' or '-')</li>
     *   <li>TZ hours (1–2 digits)</li>
     *   <li>TZ minutes (optional, 2 digits)</li>
     * </ol>
     */
    private static final Pattern SERVER_TIME_HEADER = Pattern.compile(
            "Server\\s+time:\\s*(\\d{1,2}):(\\d{2}):(\\d{2})\\s*\\(\\s*UTC\\s*"
                    + "([+-])\\s*(\\d{1,2})(?:\\s*:\\s*(\\d{2}))?\\s*\\)"
    );

    /**
     * Recognise the action verb on the headline so we know which coord pair
     * to treat as the attacker source. English Legends shows
     * "raids" / "attacks" / "scouts" / "reinforces" / "settles"; we accept
     * any of them and treat anything else as a generic "attack".
     */
    private static final Pattern ACTION_VERB = Pattern.compile(
            "(?i)\\b(raids?|attacks?|scouts?|reinforces?|settle(?:rs?|s)?)\\b"
    );

    /**
     * Parse plain text copied from the rally-point Overview tab (Ctrl+A
     * Ctrl+C from the rendered page).
     * <p>
     * <strong>Why structural rather than coord-scanning?</strong> The Overview
     * layout has TWO coordinate forms on the page and only one is the
     * attacker:
     * <pre>
     *   [defender village name] \t mark attack [attacker] raids Natars -78|150  ← SOURCE (bare)
     *   (−104|54) \t Phalanx \t Swordsman \t ...                                 ← DEFENDER (paren)
     *   Troops \t 0 \t 1 \t 0 \t ...
     *   Arrival \t in 13:17:15 hrs.at 23:30:00
     * </pre>
     * A naive "first {@code (X|Y)} wins" picked the defender's coords as the
     * source — which gave us garbage distances. The new strategy anchors on
     * the "Arrival" line (always exactly one per wave), looks backwards in a
     * small window for the bare-coord form first, then falls back to the
     * paren form when the paste doesn't include a "raids X|Y" headline.
     */
    public static List<ParsedIncomingAttack> parsePlainText(String body, Instant now) {
        List<ParsedIncomingAttack> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;

        // Anchor durations against the page's render time when available —
        // shields us from late paste-ins (copy at 10:12, paste at 10:17 → we
        // still produce the correct absolute arrival).
        Instant reference = resolveServerNow(body, now);

        Matcher anchor = ARRIVAL_ANCHOR.matcher(body);
        int prevBlockEnd = 0;
        while (anchor.find()) {
            Duration arrivalIn = parseHmsToDuration(anchor.group(1));
            if (arrivalIn == null) continue;

            int blockEnd = anchor.start();
            // A block runs from the end of the previous block (or doc start)
            // up to this Arrival line. Bound at 800 chars so a stray newline
            // can't reach back into the menu-bar coords.
            int blockStart = Math.max(prevBlockEnd, blockEnd - 800);
            String block = body.substring(blockStart, blockEnd);

            CoordPair src = findBareCoord(block);
            CoordPair def = findParenCoord(block);

            // ONLY use the bare-coord (attacker source) form. Falling back to
            // the parenthesised form would record the DEFENDER's coords as
            // the source — exactly the bug that produced "distance = 147"
            // for an attack from (-78,150) to (-104,54). When the bare form
            // is missing (cloaked attacker, fog-of-war), we leave source
            // null and the row's distance / analysis stays empty — far less
            // misleading than a fabricated number.
            Integer x = src != null ? src.x : null;
            Integer y = src != null ? src.y : null;

            String attackerName = extractAttackerName(block);
            String sourceVillageName = src != null ? src.label : null;
            boolean isAttack = detectIsAttack(block);

            Integer defX = def != null ? def.x : null;
            Integer defY = def != null ? def.y : null;
            // When the bare-coord source IS the same point as the parens
            // (defender-only paste), don't double-record it as defender — null
            // those out so the service falls back to its first-village pick.
            if (defX != null && x != null && defX.equals(x) && defY != null && y != null && defY.equals(y)) {
                defX = null;
                defY = null;
            }
            out.add(new ParsedIncomingAttack(
                    x, y,
                    sourceVillageName,
                    attackerName,
                    null,
                    reference.plus(arrivalIn),
                    1,
                    isAttack,
                    defX,
                    defY
            ));
            prevBlockEnd = anchor.end();
        }
        return out;
    }

    /** Last bare {@code X|Y} (no parens around it) in the block, if any. */
    private static CoordPair findBareCoord(String block) {
        Matcher m = BARE_COORD.matcher(block);
        CoordPair last = null;
        while (m.find()) {
            Integer x = normaliseSignedInt(m.group(1));
            Integer y = normaliseSignedInt(m.group(2));
            if (x == null || y == null) continue;
            // Coord range sanity — Travian maps fit -400..400 even on the
            // largest worlds, so anything outside that is a stray number
            // (population, troop count, etc).
            if (Math.abs(x) > 400 || Math.abs(y) > 400) continue;
            // Take the LAST bare coord in the block — closest to the Arrival
            // anchor, most likely to belong to this wave.
            last = new CoordPair(x, y, labelBefore(block, m.start()));
        }
        return last;
    }

    /** Last parenthesised {@code (X|Y)} coord in the block, if any. */
    private static CoordPair findParenCoord(String block) {
        Matcher m = PLAIN_COORD.matcher(block);
        CoordPair last = null;
        while (m.find()) {
            Integer x = normaliseSignedInt(m.group(1));
            Integer y = normaliseSignedInt(m.group(2));
            if (x == null || y == null) continue;
            last = new CoordPair(x, y, null);
        }
        return last;
    }

    /** Up to 30 chars of village-name candidate just before the bare-coord match. */
    private static String labelBefore(String block, int start) {
        int from = Math.max(0, start - 40);
        String pre = block.substring(from, start);
        // Restrict to the trailing fragment after the nearest tab/newline.
        int cut = Math.max(pre.lastIndexOf('\t'), pre.lastIndexOf('\n'));
        if (cut >= 0) pre = pre.substring(cut + 1);
        String trimmed = pre.replaceAll("\\s+", " ").trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 30) trimmed = trimmed.substring(trimmed.length() - 30);
        return trimmed;
    }

    /** Normalise "−37" (unicode minus) → -37; rejects garbage. */
    private static Integer normaliseSignedInt(String raw) {
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.replace('−', '-').trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse "H:MM[:SS]" → {@link Duration}; null on bad input. */
    private static Duration parseHmsToDuration(String raw) {
        if (raw == null) return null;
        Matcher m = PLAIN_DURATION.matcher(raw);
        if (!m.find()) return null;
        try {
            int hh = Integer.parseInt(m.group(1));
            int mm = Integer.parseInt(m.group(2));
            int ss = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            return Duration.ofSeconds(hh * 3600L + mm * 60L + ss);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Pull a sensible attacker name out of the block. We look for the action
     * verb ("raids" / "attacks" / etc.) and grab the trimmed text that
     * precedes it — that's where Travian renders the attacker's name. Falls
     * back to {@code null} when the block has no recognisable verb.
     */
    private static String extractAttackerName(String block) {
        Matcher verb = ACTION_VERB.matcher(block);
        if (!verb.find()) return null;
        int from = Math.max(0, verb.start() - 80);
        String pre = block.substring(from, verb.start());
        // Restrict to last line so we don't pick up the defender's village
        // name from the line above.
        int nl = Math.max(pre.lastIndexOf('\n'), pre.lastIndexOf('\r'));
        if (nl >= 0) pre = pre.substring(nl + 1);
        // Strip the "mark attack" link label that Travian renders before the
        // attacker's name on each row. Note: no trailing \b — Ctrl+C
        // sometimes drops the whitespace between "attack" and the attacker
        // name, leaving us with "attackNinja Kornjaca" as one word.
        String cleaned = pre.replaceAll("(?i)\\bmark\\s*attack", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Drop leading tab-delimited defender village name — anything before
        // a tab on the same line belongs to a different field.
        int tab = cleaned.lastIndexOf('\t');
        if (tab >= 0) cleaned = cleaned.substring(tab + 1).trim();
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > 60) cleaned = cleaned.substring(cleaned.length() - 60);
        return cleaned;
    }

    /**
     * "Attack vs reinforcement" is the only distinction we can reliably make
     * from plain text. {@code reinforces} / {@code reinforcement} → not an
     * attack; everything else (raids, attacks, settles, scouts) → attack.
     */
    private static boolean detectIsAttack(String block) {
        String lower = block.toLowerCase();
        if (lower.contains("reinforce")) return false;
        if (lower.contains("reinf.")) return false;
        return true;
    }

    /** Local pair record: signed coord + optional village-name label. */
    private record CoordPair(Integer x, Integer y, String label) {}

    /**
     * Resolve a reference {@link Instant} from the "Server time: HH:MM:SS
     * (UTC ±HH:MM)" header at the top of every Travian page. This is the
     * instant the user's <em>browser</em> received the page from the
     * Travian server — the point in time the "Arrival in X" countdowns are
     * measured against.
     * <p>
     * If the paste doesn't include the header (partial paste, an older skin,
     * a localised "Serverzeit:" we don't recognise), we fall back to
     * {@code callerNow}, which keeps behaviour identical to the old "use
     * Instant.now()" approach. Worst case we drift by the copy→paste latency
     * — annoying but never wrong-on-its-face like a TZ misread would be.
     * <p>
     * Sanity-clamps the result to within 12 hours of {@code callerNow} —
     * Travian's "Server time" only shows time-of-day, not the date. We try
     * the date inferred from {@code callerNow} in the server's TZ first; if
     * that lands more than 12 hours away, we shift by ±1 day to pick the
     * nearest occurrence. Catches the midnight-cross paste case.
     * <p>
     * Exposed so the service can use the same instant for {@code detectedAt}
     * on the persisted row — keeping the two ends of the travel-time
     * subtraction on the same clock.
     */
    public static Instant resolveServerNow(String body, Instant callerNow) {
        if (body == null || callerNow == null) return callerNow;
        Matcher m = SERVER_TIME_HEADER.matcher(body);
        if (!m.find()) return callerNow;
        try {
            int hh = Integer.parseInt(m.group(1));
            int mm = Integer.parseInt(m.group(2));
            int ss = Integer.parseInt(m.group(3));
            String sign = m.group(4);
            int tzHours = Integer.parseInt(m.group(5));
            int tzMinutes = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;
            int tzOffsetSeconds = (tzHours * 3600 + tzMinutes * 60)
                    * ("-".equals(sign) ? -1 : 1);
            // Clamp to legal ZoneOffset range so a garbled "(UTC +99:99)"
            // doesn't blow up the import.
            if (Math.abs(tzOffsetSeconds) > 18 * 3600) return callerNow;

            ZoneOffset zone = ZoneOffset.ofTotalSeconds(tzOffsetSeconds);
            LocalDate today = callerNow.atZone(zone).toLocalDate();
            ZonedDateTime serverNow = ZonedDateTime.of(today, LocalTime.of(hh, mm, ss), zone);
            Instant resolved = serverNow.toInstant();
            long diffSeconds = resolved.getEpochSecond() - callerNow.getEpochSecond();
            if (diffSeconds > 12 * 3600L) {
                resolved = serverNow.minusDays(1).toInstant();
            } else if (diffSeconds < -12 * 3600L) {
                resolved = serverNow.plusDays(1).toInstant();
            }
            log.debug("resolveServerNow: header={} → {} (caller now {}, skew {}s)",
                    m.group(0), resolved, callerNow, resolved.getEpochSecond() - callerNow.getEpochSecond());
            return resolved;
        } catch (RuntimeException e) {
            log.debug("resolveServerNow: malformed header '{}' — falling back to caller now ({})",
                    m.group(0), e.toString());
            return callerNow;
        }
    }

    /**
     * Loose-shape DTO holding one parsed wave. Any field can be null when the
     * page didn't surface it.
     */
    public record ParsedIncomingAttack(
            Integer sourceX,
            Integer sourceY,
            String sourceVillageName,
            String attackerName,
            String attackerAlliance,
            Instant arrivalAt,
            int waveCount,
            boolean isAttack,
            /** Defender village coords pulled from the paren-form, when known. Null on live-scrape HTML. */
            Integer defenderX,
            Integer defenderY
    ) {
        /** Backwards-compatible constructor for callers that don't supply defender coords. */
        public ParsedIncomingAttack(Integer sourceX, Integer sourceY, String sourceVillageName,
                                    String attackerName, String attackerAlliance,
                                    Instant arrivalAt, int waveCount, boolean isAttack) {
            this(sourceX, sourceY, sourceVillageName, attackerName, attackerAlliance,
                    arrivalAt, waveCount, isAttack, null, null);
        }
    }
}
