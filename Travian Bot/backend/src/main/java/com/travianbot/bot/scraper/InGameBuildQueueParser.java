package com.travianbot.bot.scraper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code #buildingList} sidebar that Travian renders on dorf1/dorf2
 * while a village has builds in progress. Each row looks roughly like:
 * <pre>
 *   Iron Mine Level 1
 *   0:00:42 hrs. done at 09:05
 * </pre>
 * with a {@code <span class="timer" value="42">} holding the remaining seconds.
 * We extract {@link Entry} records — one per in-flight build — with the name,
 * target level, and a computed finish {@link Instant}.
 * <p>
 * The parser prefers the timer's {@code value="secondsRemaining"} attribute over
 * the "done at HH:MM" string because Travian's wall-clock string is in the
 * server's zone (not the user's) — the seconds-remaining value is always
 * relative and therefore zone-independent.
 */
public final class InGameBuildQueueParser {

    private static final Logger log = LoggerFactory.getLogger(InGameBuildQueueParser.class);

    /** Matches "Iron Mine Level 3", "Main Building level 7", "Iron Mine Stufe 3" etc. */
    private static final Pattern NAME_LEVEL = Pattern.compile(
            "^(.*?)\\s+(?:Level|Stufe|Niveau|level|stufe|niveau)\\s+(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches an HH:MM:SS, H:MM:SS, or MM:SS remaining-time string. */
    private static final Pattern DURATION = Pattern.compile(
            "(?:(\\d+):)?(\\d{1,2}):(\\d{2})"
    );

    /** Extracts digits from the timer's {@code value="42"} attribute. */
    private static final Pattern INT_ONLY = Pattern.compile("^-?\\d+$");

    private InGameBuildQueueParser() {
    }

    public record Entry(String buildingName, Integer targetLevel, Instant finishesAt) {
    }

    /**
     * Parses every {@code #buildingList} row on the page. Rows that don't yield
     * a plausible finish time are skipped silently. Returns an empty list if
     * the sidebar isn't present (no builds in progress).
     */
    public static List<Entry> parse(Document doc) {
        return parse(doc, Instant.now());
    }

    /**
     * Visible for testing — override the "now" reference instant so duration
     * arithmetic is deterministic.
     */
    public static List<Entry> parse(Document doc, Instant now) {
        if (doc == null) return List.of();
        List<Entry> out = new ArrayList<>();
        Elements rows = doc.select(
                "#buildingList li, .buildingList li, "
                        + "#buildingList .buildDuration, .buildingList .buildDuration"
        );
        for (Element row : rows) {
            Entry entry = parseRow(row, now);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    /**
     * Convenience overload for callers holding raw HTML.
     */
    public static List<Entry> parse(String html) {
        if (html == null || html.isBlank()) return List.of();
        return parse(org.jsoup.Jsoup.parse(html));
    }

    private static Entry parseRow(Element row, Instant now) {
        // Name + target level usually live in an anchor or a text node above
        // the timer. Walk a few selectors and normalise whitespace.
        String label = firstNonEmpty(
                textOf(row.selectFirst(".name")),
                textOf(row.selectFirst("a")),
                row.ownText()
        );
        if (label == null) return null;
        label = label.replaceAll("\\s+", " ").trim();

        String name = label;
        Integer level = null;
        Matcher m = NAME_LEVEL.matcher(label);
        if (m.matches()) {
            name = m.group(1).trim();
            try {
                level = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                // fall through — keep level null
            }
        }
        if (name.isEmpty()) return null;

        Integer seconds = extractSecondsRemaining(row);
        if (seconds == null || seconds < 0) {
            log.debug("InGameBuildQueueParser: no duration for row '{}'", label);
            return null;
        }
        Instant finishesAt = now.plusSeconds(seconds);
        return new Entry(name, level, finishesAt);
    }

    /**
     * Resolves a remaining-seconds count for the row. Tries, in order:
     * <ol>
     *   <li>{@code <span class="timer" value="NN">} — numeric seconds remaining.
     *       This is Travian's own JS countdown hook and is authoritative.</li>
     *   <li>A textual HH:MM:SS inside the row.</li>
     * </ol>
     */
    private static Integer extractSecondsRemaining(Element row) {
        Element timer = row.selectFirst("span.timer, .timer");
        if (timer != null) {
            String attrCandidates = firstNonEmpty(
                    timer.attr("value"),
                    timer.attr("data-end"),
                    timer.attr("data-remaining")
            );
            if (attrCandidates != null) {
                String trimmed = attrCandidates.trim();
                if (INT_ONLY.matcher(trimmed).matches()) {
                    try {
                        int n = Integer.parseInt(trimmed);
                        if (n >= 0) return n;
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
            Integer fromText = parseDurationSeconds(timer.text());
            if (fromText != null) return fromText;
        }
        // Last resort: scan the whole row text.
        return parseDurationSeconds(row.text());
    }

    /**
     * Visible for testing. Parses an HH:MM:SS or MM:SS string into seconds.
     * Returns null on no match or obviously invalid input (negative, >72 h).
     */
    static Integer parseDurationSeconds(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = DURATION.matcher(text);
        if (!m.find()) return null;
        try {
            int hours = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
            int minutes = Integer.parseInt(m.group(2));
            int seconds = Integer.parseInt(m.group(3));
            if (minutes >= 60 || seconds >= 60) return null;
            long total = hours * 3600L + minutes * 60L + seconds;
            if (total < 0 || total > 72L * 3600L) return null;
            return (int) total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOf(Element el) {
        if (el == null) return null;
        String t = el.text();
        return t == null || t.isBlank() ? null : t;
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }
}
