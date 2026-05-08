package com.travianbot.statistics.scraper;

import com.travianbot.statistics.domain.StatCategory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the T4.6 Legends {@code /statistics/player/top10} page into one
 * ranking list per {@link StatCategory}. The page contains four "…of the week"
 * mini-tables, all with identical markup:
 * <pre>{@code
 * <h4 class="round small top top10_offs">PvP of the week</h4>
 * <table class="top10" id="top10_offs"> …rows… </table>
 *
 * <h4 class="round small top top10_offs">PvE of the week</h4>
 * <table class="top10" id="top10_offs"> …rows… </table>    // duplicate id
 *
 * <h4 class="round small spacer top top10_defs">Defenders of the week</h4>
 * <table class="top10" id="top10_defs"> …rows… </table>
 *
 * <h4 class="round small spacer top top10_raiders">Robbers of the week</h4>
 * <table class="top10" id="top10_raiders"> …rows… </table>
 * }</pre>
 *
 * <p>Mapping to categories:
 * <ul>
 *   <li>First {@code #top10_offs} table → {@link StatCategory#PVP}</li>
 *   <li>Second {@code #top10_offs} table → {@link StatCategory#PVE}</li>
 *   <li>{@code #top10_defs} → {@link StatCategory#DEFENSE}</li>
 *   <li>{@code #top10_raiders} → {@link StatCategory#RAIDERS}</li>
 * </ul>
 *
 * <p>Each row looks like:
 * <pre>{@code
 * <tr class="hover">
 *   <td class="ra fc">1.&nbsp;</td>
 *   <td class="tribe fc"><i class="tribe9_medium"></i></td>
 *   <td class="pla"><a href="/profile/285">Mr. Nimbus</a></td>
 *   <td class="val lc">‭1,092‬</td>
 * </tr>
 * }</pre>
 *
 * <p>Numeric cells are wrapped in invisible U+202D / U+202C directional marks
 * and contain thousands separators (comma). The digit-stripping regex in
 * {@link #parseLongOrNull} removes all of that.
 *
 * <p>Rows with {@code class="own hl"} (the logged-in player's rank, appended
 * below the leaderboard) and the {@code noGapTop} separator row are skipped —
 * neither is a ranking entry we want to store.
 */
public final class Top10PageParser {

    private static final Logger log = LoggerFactory.getLogger(Top10PageParser.class);
    private static final int MAX_ROWS_PER_TABLE = 10;

    private Top10PageParser() {
    }

    /**
     * Parse all four category tables. Returns a map keyed by {@link StatCategory};
     * categories whose table is missing from the page are absent from the map
     * (callers should treat "no key" as "no data for that category this
     * capture" — not a hard error).
     */
    public static Map<StatCategory, List<ParsedTop10Row>> parseAll(String html) {
        Document doc = Jsoup.parse(html);
        EnumMap<StatCategory, List<ParsedTop10Row>> out = new EnumMap<>(StatCategory.class);

        // PvP and PvE share id=top10_offs (Travian ships duplicate ids). Their
        // order on the page is stable — PvP first, PvE second — so we rely on
        // position here. If the page only ships one #top10_offs we take it as
        // PVP and log a warning.
        Elements offsTables = doc.select("table.top10#top10_offs");
        if (offsTables.isEmpty()) {
            log.warn("No #top10_offs tables found on top10 page — HTML length={}, total tables={}",
                    html.length(), doc.select("table").size());
        } else {
            List<ParsedTop10Row> pvp = readTable(offsTables.get(0));
            if (!pvp.isEmpty()) out.put(StatCategory.PVP, pvp);

            if (offsTables.size() >= 2) {
                List<ParsedTop10Row> pve = readTable(offsTables.get(1));
                if (!pve.isEmpty()) out.put(StatCategory.PVE, pve);
            } else {
                log.debug("Only one #top10_offs table found — PvE will be skipped this capture");
            }
        }

        Element defs = doc.selectFirst("table.top10#top10_defs");
        if (defs != null) {
            List<ParsedTop10Row> rows = readTable(defs);
            if (!rows.isEmpty()) out.put(StatCategory.DEFENSE, rows);
        } else {
            log.debug("Defenders-of-the-week table not found on top10 page");
        }

        Element raiders = doc.selectFirst("table.top10#top10_raiders");
        if (raiders != null) {
            List<ParsedTop10Row> rows = readTable(raiders);
            if (!rows.isEmpty()) out.put(StatCategory.RAIDERS, rows);
        } else {
            log.debug("Robbers-of-the-week table not found on top10 page");
        }

        return out;
    }

    /**
     * Reads one .top10 table. Iterates {@code tbody > tr.hover} (the actual
     * ranking rows), skipping the separator and the {@code tr.own.hl} row
     * (logged-in player's rank). Caps at {@link #MAX_ROWS_PER_TABLE}.
     */
    private static List<ParsedTop10Row> readTable(Element table) {
        Elements rows = table.select("tbody > tr.hover");
        List<ParsedTop10Row> out = new ArrayList<>(Math.min(rows.size(), MAX_ROWS_PER_TABLE));
        for (Element tr : rows) {
            if (out.size() >= MAX_ROWS_PER_TABLE) break;
            Elements cells = tr.select("> td");
            if (cells.size() < 4) continue;

            Integer rank = parseIntOrNull(cells.get(0).text());
            String player = textOrNull(cells.get(2));
            Long score = parseLongOrNull(cells.get(3).text());
            if (rank == null || player == null) continue;

            out.add(new ParsedTop10Row(rank, player, score));
        }
        return out;
    }

    private static String textOrNull(Element el) {
        if (el == null) return null;
        String t = el.text();
        if (t == null) return null;
        t = t.trim();
        return t.isEmpty() ? null : t;
    }

    private static Integer parseIntOrNull(String raw) {
        String digits = stripNonDigits(raw);
        if (digits.isEmpty() || "-".equals(digits)) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String raw) {
        String digits = stripNonDigits(raw);
        if (digits.isEmpty() || "-".equals(digits)) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Strips everything non-digit (keeping a leading minus). Handles thousands
     * separators, NBSP, and the U+202D / U+202C directional marks Travian wraps
     * numbers in.
     */
    private static String stripNonDigits(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9-]", "");
    }
}
