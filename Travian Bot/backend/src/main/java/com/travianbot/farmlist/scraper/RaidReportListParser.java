package com.travianbot.farmlist.scraper;

import org.jsoup.Jsoup;
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
 * Parses the T4.6 Legends {@code berichte.php} report list page.
 * <p>
 * Each row in the report table carries:
 * <ul>
 *   <li>an icon class that classifies the report — we keep raid/attack
 *       outcome icons ({@code iReport0..4}) and skip everything else
 *       (support, scout, spy, trade).</li>
 *   <li>a clickable link / onclick to {@code berichte.php?id=N}. We extract
 *       N — that's our {@code travianReportId}.</li>
 *   <li>a "Sent" / "When" column with a {@code .timer} span carrying
 *       {@code value=<unix-seconds>}.</li>
 *   <li>a target column with the village name and coords. Coords are either
 *       a {@code (N|M)} text fragment or an {@code <a href="karte.php?x=N&y=M">}
 *       link — we take whichever we find first.</li>
 * </ul>
 * <p>
 * Skin coverage: T4.6 servers vary on which CSS class wraps the table
 * ({@code .tab1}, {@code table.transparent}, etc) so we use a tolerant
 * selector and let row-level parsing reject anything that doesn't have
 * an extractable report id.
 */
public final class RaidReportListParser {

    private static final Logger log = LoggerFactory.getLogger(RaidReportListParser.class);

    /** Matches {@code berichte.php?id=12345} and similar — we want group 1. */
    private static final Pattern REPORT_ID_PATTERN = Pattern.compile("[?&]id=(\\d+)");

    /** Permissive coord matcher — handles {@code (-12|7)}, {@code -12|7}, {@code (-12 | 7)}. */
    private static final Pattern COORDS_PATTERN = Pattern.compile("\\(?\\s*(-?\\d+)\\s*[|,]\\s*(-?\\d+)\\s*\\)?");

    /** Matches {@code x=N} in a karte.php link. */
    private static final Pattern KARTE_X = Pattern.compile("[?&]x=(-?\\d+)");
    private static final Pattern KARTE_Y = Pattern.compile("[?&]y=(-?\\d+)");

    /**
     * Travian's icon classes we treat as "raid-like" and care about.
     * 0 — perfect raid (no losses), 1 — raid with some defender losses,
     * 2 — raid with attacker losses, 3 — defeat, 4 — total defeat.
     * Everything else (scout, support, trade, adventure...) is skipped.
     */
    private static final List<String> RAID_ICON_CLASSES = List.of(
            "iReport0", "iReport1", "iReport2", "iReport3", "iReport4"
    );

    private RaidReportListParser() {
    }

    /**
     * Parses the report-list HTML and returns one row per raid-shaped report.
     * Returns an empty list if the markup looks completely foreign — never
     * throws.
     */
    public static List<ParsedReportRowSummary> parse(String html) {
        if (html == null || html.isBlank()) return List.of();

        Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (RuntimeException e) {
            log.warn("Could not parse report-list HTML: {}", e.getMessage());
            return List.of();
        }

        List<ParsedReportRowSummary> out = new ArrayList<>();

        // The reports table has varied between skins — try a few likely
        // containers and fall back to all `<tr>`s under any `.tab1`.
        Elements rows = doc.select(
                "table.tab1 tr[onclick], "
                        + "table.transparent tr[onclick], "
                        + "table#overview tr[onclick], "
                        + "table.tableType tr[onclick]");
        if (rows.isEmpty()) {
            rows = doc.select("table.tab1 tbody tr, table.tableType tbody tr");
        }
        if (rows.isEmpty()) {
            log.debug("No report rows found in HTML — page layout may have changed");
            return List.of();
        }

        for (Element row : rows) {
            try {
                ParsedReportRowSummary parsed = parseRow(row);
                if (parsed != null) out.add(parsed);
            } catch (RuntimeException e) {
                log.debug("Skipping malformed report row: {}", e.getMessage());
            }
        }
        return out;
    }

    private static ParsedReportRowSummary parseRow(Element row) {
        // 1) Icon class — must be raid-like.
        if (!isRaidRow(row)) return null;

        // 2) travianReportId — pulled from onclick or any berichte.php?id= link.
        Long reportId = extractReportId(row);
        if (reportId == null) return null;

        // 3) Target coords + name. The "subject" column usually contains both.
        Integer x = null, y = null;
        String villageName = null;

        Element karteLink = row.selectFirst("a[href*=karte.php]");
        if (karteLink != null) {
            Matcher mx = KARTE_X.matcher(karteLink.attr("href"));
            Matcher my = KARTE_Y.matcher(karteLink.attr("href"));
            if (mx.find() && my.find()) {
                x = parseIntOrNull(mx.group(1));
                y = parseIntOrNull(my.group(1));
            }
            villageName = textOrNull(karteLink);
        }
        if (x == null || y == null) {
            // Fallback: parse "(N|M)" out of the whole row text.
            Matcher m = COORDS_PATTERN.matcher(row.text());
            if (m.find()) {
                x = parseIntOrNull(m.group(1));
                y = parseIntOrNull(m.group(2));
            }
        }

        // 4) Sent timestamp — `.timer` span has a `value=<unix-seconds>` attr
        // on every Travian skin I've seen. Falls back to null if absent.
        Instant timestamp = null;
        Element timer = row.selectFirst("span.timer, .timer");
        if (timer != null) {
            Long unixSeconds = parseLongOrNull(timer.attr("value"));
            if (unixSeconds != null) timestamp = Instant.ofEpochSecond(unixSeconds);
        }

        // 5) Subject line — what the report itself was titled. Used as a
        // fallback for villageName if the karte link didn't carry one.
        String subject = textOrNull(row.selectFirst("td.subject, td.subj, a[href*=berichte.php]"));
        if (villageName == null) villageName = subject;

        return new ParsedReportRowSummary(reportId, x, y, villageName, timestamp);
    }

    /** True if the row's icon classes mark it as raid/attack-related. */
    private static boolean isRaidRow(Element row) {
        Elements iconNodes = row.select("img.iReport, span.iReport, img[class*=iReport], "
                + "[class*=iReport0], [class*=iReport1], [class*=iReport2], "
                + "[class*=iReport3], [class*=iReport4]");
        if (iconNodes.isEmpty()) {
            // Some skins put the icon class on the row itself.
            for (String c : RAID_ICON_CLASSES) {
                if (row.hasClass(c) || row.html().contains(c)) return true;
            }
            return false;
        }
        for (Element icon : iconNodes) {
            String cls = icon.className();
            for (String marker : RAID_ICON_CLASSES) {
                if (cls.contains(marker)) return true;
            }
        }
        return false;
    }

    private static Long extractReportId(Element row) {
        String onclick = row.attr("onclick");
        if (onclick != null && !onclick.isEmpty()) {
            Matcher m = REPORT_ID_PATTERN.matcher(onclick);
            if (m.find()) return parseLongOrNull(m.group(1));
        }
        Element link = row.selectFirst("a[href*=berichte.php]");
        if (link != null) {
            Matcher m = REPORT_ID_PATTERN.matcher(link.attr("href"));
            if (m.find()) return parseLongOrNull(m.group(1));
        }
        return null;
    }

    private static String textOrNull(Element el) {
        if (el == null) return null;
        String t = el.text();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * One row of the report-list table that we care about. Fields are nullable
     * — the parser is permissive and the downstream service decides what to
     * do with partial rows.
     *
     * @param travianReportId Travian's internal {@code id=N} identifier
     * @param targetX X-coordinate of the raid target, or null if unparseable
     * @param targetY Y-coordinate of the raid target, or null if unparseable
     * @param targetVillageName visible village/subject text, or null
     * @param raidReturnedAt timestamp the report became visible, or null
     */
    public record ParsedReportRowSummary(
            Long travianReportId,
            Integer targetX,
            Integer targetY,
            String targetVillageName,
            Instant raidReturnedAt
    ) {}
}
