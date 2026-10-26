package com.travianbot.farmlist.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an individual {@code berichte.php?id=N} battle report.
 * <p>
 * Heuristics per field (each can fall back to null/0 if the markup doesn't
 * match — we never throw):
 * <ul>
 *   <li><b>outcome</b> — first {@code img.iReport} / {@code span.iReport}
 *       class on the page. {@code iReport0} → VICTORY_NO_LOSS,
 *       {@code iReport1/2} → VICTORY_WITH_LOSS (raid landed but defenders
 *       took out some troops), {@code iReport3/4} → DEFEAT. Anything else
 *       falls back to UNKNOWN.</li>
 *   <li><b>loot</b> — Travian renders the four resources in
 *       {@code .resource} icons in the bounty section. We pluck the integer
 *       text out of the spans that follow {@code .r1 / .r2 / .r3 / .r4}.</li>
 *   <li><b>carry capacity</b> — bounty footer "X / Y" string. We treat Y as
 *       total capacity and {@code wasFullHaul = (X == Y)}.</li>
 *   <li><b>friendly losses</b> — the attacker's troop table has two numeric
 *       rows: troops sent and troops returned. Loss = sum(sent) -
 *       sum(returned) per column. We sum across all columns.</li>
 *   <li><b>enemy losses</b> — same logic on the defender's table when present.</li>
 *   <li><b>target coords / name</b> — from the report headline {@code h3}
 *       or the {@code .villageList .village} breadcrumb.</li>
 * </ul>
 */
public final class RaidReportDetailParser {

    private static final Logger log = LoggerFactory.getLogger(RaidReportDetailParser.class);

    private static final Pattern COORDS_PATTERN = Pattern.compile("\\(?\\s*(-?\\d+)\\s*[|,]\\s*(-?\\d+)\\s*\\)?");
    private static final Pattern NUMERIC = Pattern.compile("-?\\d{1,9}");
    /** Matches "1234 / 5678" — group 1 used, group 2 total. */
    private static final Pattern CARRY_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private RaidReportDetailParser() {
    }

    /**
     * Parse the full HTML of {@code berichte.php?id=N}. Returns a record with
     * every parsed field, leaving any unparseable field at its zero/null
     * default — callers MUST be prepared to see partial data.
     */
    public static ParsedReportDetail parse(String html) {
        if (html == null || html.isBlank()) return ParsedReportDetail.empty();

        Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (RuntimeException e) {
            log.warn("Could not parse report-detail HTML: {}", e.getMessage());
            return ParsedReportDetail.empty();
        }

        String outcome = parseOutcome(doc);
        Coords target = parseTargetCoords(doc);
        String name = parseTargetName(doc);
        Loot loot = parseLoot(doc);
        Carry carry = parseCarry(doc);
        int ourLost = sumLossesForSide(doc, true);
        int enemyLost = sumLossesForSide(doc, false);

        return new ParsedReportDetail(
                outcome,
                target.x(), target.y(),
                name,
                loot.wood(), loot.clay(), loot.iron(), loot.crop(),
                carry.total(),
                carry.full(),
                ourLost,
                enemyLost
        );
    }

    /** Maps Travian's icon class to one of our four outcome buckets. */
    private static String parseOutcome(Document doc) {
        Element icon = doc.selectFirst(
                "img.iReport0, img.iReport1, img.iReport2, img.iReport3, img.iReport4,"
                        + "span.iReport0, span.iReport1, span.iReport2, span.iReport3, span.iReport4,"
                        + "[class*=iReport0], [class*=iReport1], [class*=iReport2], "
                        + "[class*=iReport3], [class*=iReport4]"
        );
        if (icon == null) return "UNKNOWN";
        String cls = icon.className();
        if (cls.contains("iReport0")) return "VICTORY_NO_LOSS";
        if (cls.contains("iReport1") || cls.contains("iReport2")) return "VICTORY_WITH_LOSS";
        if (cls.contains("iReport3") || cls.contains("iReport4")) return "DEFEAT";
        return "UNKNOWN";
    }

    /** Target coords are usually in the headline or attached as a karte link. */
    private static Coords parseTargetCoords(Document doc) {
        // Prefer a karte.php link to a coord-shaped text fragment because the
        // link is unambiguous and the text scrape can pick up the attacker's
        // home coords by accident.
        Element karte = doc.selectFirst("a[href*=karte.php]");
        if (karte != null) {
            Matcher mx = Pattern.compile("[?&]x=(-?\\d+)").matcher(karte.attr("href"));
            Matcher my = Pattern.compile("[?&]y=(-?\\d+)").matcher(karte.attr("href"));
            if (mx.find() && my.find()) {
                return new Coords(parseIntOrNull(mx.group(1)), parseIntOrNull(my.group(1)));
            }
        }
        Element headline = doc.selectFirst("h3, .titleInHeader, #attacker, .reportTitle");
        if (headline != null) {
            Matcher m = COORDS_PATTERN.matcher(headline.text());
            if (m.find()) return new Coords(parseIntOrNull(m.group(1)), parseIntOrNull(m.group(2)));
        }
        return new Coords(null, null);
    }

    private static String parseTargetName(Document doc) {
        Element link = doc.selectFirst(".villageList .village a, .infos .village a, h3 a[href*=karte.php]");
        if (link != null) {
            String t = link.text();
            if (t != null && !t.isBlank()) return t.trim();
        }
        Element h3 = doc.selectFirst("h3, .titleInHeader");
        if (h3 != null) {
            String t = h3.text();
            if (t != null && !t.isBlank()) return t.trim();
        }
        return null;
    }

    /**
     * Pulls the four loot integers out of the bounty section. Looks for
     * elements whose class contains {@code r1..r4} (the canonical resource
     * markers on every T4.6 skin) and reads the first numeric value out of
     * each one's text.
     */
    private static Loot parseLoot(Document doc) {
        Integer wood = readResource(doc, "r1");
        Integer clay = readResource(doc, "r2");
        Integer iron = readResource(doc, "r3");
        Integer crop = readResource(doc, "r4");
        return new Loot(wood, clay, iron, crop);
    }

    private static Integer readResource(Document doc, String marker) {
        // Two common shapes:
        //   <span class="resource"><i class="r1"></i> 1,234</span>
        //   <td><span class="r1"></span><span class="num">1234</span></td>
        Elements nodes = doc.select("." + marker);
        for (Element n : nodes) {
            // Walk up to the closest containing cell/span to find the value.
            Element host = n.parent();
            if (host == null) continue;
            String text = host.text();
            Integer v = firstIntIn(text);
            if (v != null) return v;
        }
        // Fallback: a sibling pattern — `.resource` block listing wood/clay/iron/crop in order.
        Elements resources = doc.select(".resource, .restypes .iconNum");
        if (resources.size() >= 4) {
            int index = switch (marker) {
                case "r1" -> 0; case "r2" -> 1; case "r3" -> 2; case "r4" -> 3;
                default -> -1;
            };
            if (index >= 0) return firstIntIn(resources.get(index).text());
        }
        return null;
    }

    private static Carry parseCarry(Document doc) {
        // The carry summary is usually a "1234 / 5678" string in the bounty
        // footer. We grep the page text for the pattern, accepting whichever
        // X/Y pair we find first (always the bounty figure on Travian).
        Element bounty = doc.selectFirst(".infos .carry, .bounty, .carryInfo, .reportTitle + table");
        String text = (bounty != null) ? bounty.text() : doc.body() == null ? "" : doc.body().text();
        Matcher m = CARRY_PATTERN.matcher(text);
        if (m.find()) {
            int used = parseIntOrNull(m.group(1)) == null ? 0 : Integer.parseInt(m.group(1));
            int total = parseIntOrNull(m.group(2)) == null ? 0 : Integer.parseInt(m.group(2));
            boolean full = total > 0 && used >= total;
            return new Carry(total, full);
        }
        return new Carry(null, false);
    }

    /**
     * Sums per-column losses on either the attacker or defender troop table.
     * Travian renders two numeric rows per side: troops sent and troops
     * returned. Per-column loss = sent - returned (clamped to zero); we sum
     * across all columns to get the total unit loss.
     */
    private static int sumLossesForSide(Document doc, boolean attacker) {
        // The attacker table has class `.troopHeadline` or is the first
        // numeric table; defender is the second. We pick by index instead of
        // class because skin classes vary.
        Elements troopTables = doc.select("table.troopInfo, table.troop_info, table#attacker, table#defender");
        if (troopTables.isEmpty()) {
            troopTables = doc.select(".in_attack table, .reportHelpfulInfo table");
        }
        if (troopTables.size() < (attacker ? 1 : 2)) return 0;
        Element tbl = troopTables.get(attacker ? 0 : 1);
        return sumColumnDiffs(tbl);
    }

    /**
     * Given a troop table with two numeric rows (sent / returned), compute
     * the per-column loss and sum across columns. Returns 0 if the table is
     * malformed or we can't find two numeric rows.
     */
    private static int sumColumnDiffs(Element table) {
        if (table == null) return 0;
        Elements rows = table.select("tr");
        int[] sent = null;
        int[] back = null;
        for (Element r : rows) {
            Elements cells = r.select("td");
            int[] nums = collectIntCells(cells);
            if (nums == null) continue;
            if (sent == null) sent = nums;
            else if (back == null) { back = nums; break; }
        }
        if (sent == null || back == null) return 0;
        int n = Math.min(sent.length, back.length);
        int losses = 0;
        for (int i = 0; i < n; i++) {
            int d = sent[i] - back[i];
            if (d > 0) losses += d;
        }
        return losses;
    }

    /**
     * Returns the numeric values in this row's cells if and only if every
     * non-empty cell is parseable as an integer. This lets us distinguish a
     * "header" row ("Attacker", icons, text labels) from a "data" row
     * (numeric troop counts).
     */
    private static int[] collectIntCells(Elements cells) {
        int[] out = new int[cells.size()];
        int populated = 0;
        for (int i = 0; i < cells.size(); i++) {
            String text = cells.get(i).text();
            if (text == null || text.isBlank()) { out[i] = 0; continue; }
            Integer v = firstIntIn(text);
            if (v == null) {
                // Cell has non-numeric text — likely a header row.
                return null;
            }
            out[i] = v;
            populated++;
        }
        return (populated > 0) ? out : null;
    }

    private static Integer firstIntIn(String s) {
        if (s == null) return null;
        // Strip Travian's thousand separators (a non-breaking space or comma).
        String cleaned = s.replace(" ", "").replace(",", "");
        Matcher m = NUMERIC.matcher(cleaned);
        if (m.find()) {
            try { return Integer.parseInt(m.group()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private record Coords(Integer x, Integer y) {}
    private record Loot(Integer wood, Integer clay, Integer iron, Integer crop) {}
    private record Carry(Integer total, boolean full) {}

    /**
     * Parsed contents of a single battle report. Any field can be null/0 when
     * the markup didn't yield it — callers MUST tolerate partial data.
     */
    public record ParsedReportDetail(
            String outcome,
            Integer targetX,
            Integer targetY,
            String targetVillageName,
            Integer woodLooted,
            Integer clayLooted,
            Integer ironLooted,
            Integer cropLooted,
            Integer carryCapacityTotal,
            boolean wasFullHaul,
            int ourTroopsLost,
            int enemyTroopsLost
    ) {
        public static ParsedReportDetail empty() {
            return new ParsedReportDetail(
                    "UNKNOWN", null, null, null,
                    0, 0, 0, 0,
                    null, false, 0, 0
            );
        }
    }
}
