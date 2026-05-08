package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.AdventureDifficulty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code /hero/adventures} page into a list of
 * {@link ParsedHeroAdventure}. Works across a few skins — T4.6 Legends is the
 * current target, but we still try the older layouts as fallbacks.
 * <p>
 * The page always renders one row per adventure inside a table. Each row has
 * coordinates, a difficulty marker, a duration/timer column, and a "Start"
 * link that carries a {@code mapId}. The selectors below are intentionally
 * layered: a row must have coordinates OR a mapId to be considered valid —
 * this keeps us from picking up table-header/footer rows in fallback mode.
 */
public final class HeroAdventuresParser {

    private static final Pattern MAP_ID = Pattern.compile("[?&]mapId=(\\d+)");
    /** Coordinate pair forms we accept: (X|Y), (X,Y), (X/Y), or "X|Y" loose. */
    private static final Pattern COORD_PAIR =
            Pattern.compile("\\(?(-?\\d{1,4})\\s*[|,/]\\s*(-?\\d{1,4})\\)?");
    /** Duration in "HH:MM:SS" or "MM:SS" — common for the duration column. */
    private static final Pattern DURATION = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    private HeroAdventuresParser() {
    }

    public static List<ParsedHeroAdventure> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<ParsedHeroAdventure> out = new ArrayList<>();

        // Try the most specific selectors first. T4.6 Legends: #adventureListForm
        // wraps a <table> whose tbody rows are the adventures. Older skins used
        // .adventureList / table.adventures.
        Elements rows = doc.select(
                "#adventureListForm table tbody tr, "
                        + "#adventureListForm tbody tr, "
                        + "table#adventureListForm tbody tr, "
                        + "table.adventureList tbody tr, "
                        + ".adventureList tbody tr, "
                        + "table.adventures tbody tr, "
                        + "#content table.adventures tbody tr, "
                        + "#heroAdventure tbody tr"
        );

        // Last-resort: scan every table row on the page and keep the ones that
        // look like adventures (have a mapId link or a coord pair). Works even
        // when class names drifted.
        if (rows.isEmpty()) {
            rows = doc.select("table tbody tr, table tr");
        }

        for (Element row : rows) {
            ParsedHeroAdventure parsed = parseRow(row);
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    private static ParsedHeroAdventure parseRow(Element row) {
        // Skip obvious non-data rows (headers, empty-state placeholders).
        if (row.selectFirst("th") != null && row.selectFirst("td") == null) return null;
        String rowText = row.text();
        if (rowText == null || rowText.isBlank()) return null;

        Integer x = parseCoord(row, ".coordinateX, .xCoord, .coords .x, span.coordinateX");
        Integer y = parseCoord(row, ".coordinateY, .yCoord, .coords .y, span.coordinateY");
        if (x == null || y == null) {
            // Fallback: parse "(X|Y)" anywhere in the row.
            Matcher m = COORD_PAIR.matcher(rowText);
            if (m.find()) {
                x = parseIntOrNull(m.group(1));
                y = parseIntOrNull(m.group(2));
            }
        }

        Long mapId = parseMapId(row);
        // Require at least a coord pair OR a mapId link — without either this
        // isn't an adventure row.
        if (mapId == null && (x == null || y == null)) return null;

        AdventureDifficulty difficulty = parseDifficulty(row);
        Instant endAt = parseEndAt(row);
        return new ParsedHeroAdventure(
                x == null ? 0 : x,
                y == null ? 0 : y,
                difficulty,
                endAt,
                mapId
        );
    }

    private static Integer parseCoord(Element row, String selector) {
        Element el = row.selectFirst(selector);
        if (el == null) return null;
        return parseIntOrNull(el.text());
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9-]", "");
        if (d.isEmpty() || d.equals("-")) return null;
        try {
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AdventureDifficulty parseDifficulty(Element row) {
        // Current T4.6 renders the difficulty as an <img class="difficulty0"> /
        // "difficulty1" inside the difficulty cell. New skins sometimes use an
        // <i class="difficulty-0"> icon or an <img alt="difficulty 1">.
        Element img = row.selectFirst(
                "img.difficulty0, img.difficulty1, img[class*=difficulty], "
                        + "i[class*=difficulty], [data-difficulty]"
        );
        if (img != null) {
            String cls = img.className().toLowerCase(Locale.ROOT);
            String alt = img.attr("alt").toLowerCase(Locale.ROOT);
            String dd  = img.attr("data-difficulty");
            if (cls.contains("difficulty1") || cls.contains("difficulty-1")
                    || cls.contains("hard") || alt.contains("hard") || alt.contains("difficult")
                    || "1".equals(dd)) return AdventureDifficulty.HARD;
            return AdventureDifficulty.NORMAL;
        }
        String txt = row.text().toLowerCase(Locale.ROOT);
        if (txt.contains("difficult") || txt.contains("schwer") || txt.contains("hard"))
            return AdventureDifficulty.HARD;
        if (txt.contains("normal") || txt.contains("easy") || txt.contains("leicht"))
            return AdventureDifficulty.NORMAL;
        return AdventureDifficulty.UNKNOWN;
    }

    private static Instant parseEndAt(Element row) {
        // Adventure cells typically carry a <span class="timer" value="seconds">
        // which counts down to expiry. On newer skins the timer is
        // <span class="timer" data-end="secondsRemaining"> or the remaining time
        // is rendered directly as "HH:MM:SS" text.
        Element timer = row.selectFirst(
                "span.timer, .timer, .expires, .endTime, [data-end], [data-remaining]"
        );
        if (timer != null) {
            String val = firstNonBlank(
                    timer.attr("value"),
                    timer.attr("data-end"),
                    timer.attr("data-remaining")
            );
            if (val != null && !val.isBlank()) {
                try {
                    long seconds = Long.parseLong(val.replaceAll("[^0-9]", ""));
                    if (seconds > 0) return Instant.now().plusSeconds(seconds);
                } catch (NumberFormatException ignored) {
                    // fall through to text parsing
                }
            }
            Matcher m = DURATION.matcher(timer.text());
            if (m.find()) {
                long h, mm, ss;
                if (m.group(3) != null) {
                    h  = Long.parseLong(m.group(1));
                    mm = Long.parseLong(m.group(2));
                    ss = Long.parseLong(m.group(3));
                } else {
                    h  = 0L;
                    mm = Long.parseLong(m.group(1));
                    ss = Long.parseLong(m.group(2));
                }
                long secs = h * 3600 + mm * 60 + ss;
                if (secs > 0) return Instant.now().plusSeconds(secs);
            }
        }
        return null;
    }

    private static Long parseMapId(Element row) {
        Element a = row.selectFirst(
                "a.gotoAdventure, a[href*=mapId], a[href*=start_adventure], "
                        + "input[name=mapId]"
        );
        if (a == null) return null;
        String src = firstNonBlank(a.attr("href"), a.attr("value"));
        if (src == null) return null;
        if (src.matches("\\d+")) {
            try { return Long.parseLong(src); } catch (NumberFormatException ignored) { return null; }
        }
        Matcher m = MAP_ID.matcher(src);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
