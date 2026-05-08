package com.travianbot.bot.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the T4.6 Legends rally-point "Farm lists" tab.
 * <p>
 * In T4.6 the Farm-lists tab is at {@code /build.php?gid=16&tt=99} (or just
 * {@code gid=16} with the lists tab selected). The markup has one container
 * per list with a hidden {@code data-listid} and a table of raid targets.
 * Each target row carries:
 * <ul>
 *   <li>{@code data-targetid} — the list-item id ({@code slotId} on send form)</li>
 *   <li>{@code .village} — target name</li>
 *   <li>{@code .coords} — coords like {@code (-12|7)}</li>
 *   <li>one input per troop type, name of the form {@code troop[tN]} or {@code t1}/{@code t2}...</li>
 *   <li>a checkbox {@code .markSend} that's pre-checked when the target is active</li>
 * </ul>
 * <p>
 * Skins vary (plus + servers) so the parser tries multiple selectors and is
 * permissive about malformed rows — a target with no coords is simply skipped.
 */
public final class FarmlistPageParser {

    private static final Logger log = LoggerFactory.getLogger(FarmlistPageParser.class);

    private static final Pattern COORDS = Pattern.compile("\\(?\\s*(-?\\d+)\\s*[|,]\\s*(-?\\d+)\\s*\\)?");
    private static final Pattern TROOP_INPUT = Pattern.compile("^(?:troop\\[)?t(\\d+)\\]?$");
    private static final Pattern LIST_ID_IN_HREF = Pattern.compile("[?&]lid=(\\d+)");

    private FarmlistPageParser() {
    }

    public static List<ParsedFarmlist> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<ParsedFarmlist> out = new ArrayList<>();

        // Standard T4.6 skin: each list is wrapped in <div class="raidList" data-listid="...">
        Elements lists = doc.select("div.raidList, div.farmList, div[data-listid]");
        if (lists.isEmpty()) {
            log.warn("No farmlist containers found — rally-point layout may have changed");
            return out;
        }

        for (Element list : lists) {
            ParsedFarmlist fl = parseSingleList(list);
            if (fl != null) out.add(fl);
        }
        return out;
    }

    private static ParsedFarmlist parseSingleList(Element list) {
        Long listId = parseIntOrNullAsLong(list.attr("data-listid"));
        if (listId == null) {
            Element editLink = list.selectFirst("a[href*=\"lid=\"]");
            if (editLink != null) {
                Matcher m = LIST_ID_IN_HREF.matcher(editLink.attr("href"));
                if (m.find()) listId = Long.parseLong(m.group(1));
            }
        }

        String name = textOrNull(list.selectFirst(".listName, .name, h4, h3"));
        if (name == null) name = "Unnamed list";

        List<ParsedFarmlistTarget> targets = new ArrayList<>();
        Elements rows = list.select("tr.slotRow, tr[data-targetid], tr.raidSlot");
        if (rows.isEmpty()) {
            // Fallback: some skins render rows directly under the list div.
            rows = list.select("tr");
        }

        for (Element row : rows) {
            ParsedFarmlistTarget target = parseRow(row);
            if (target != null) targets.add(target);
        }

        if (targets.isEmpty()) {
            log.debug("Farmlist {} (id={}) had no parseable targets", name, listId);
        }
        return new ParsedFarmlist(listId, name, targets);
    }

    private static ParsedFarmlistTarget parseRow(Element row) {
        Long targetId = parseIntOrNullAsLong(row.attr("data-targetid"));
        if (targetId == null) targetId = parseIntOrNullAsLong(row.attr("data-id"));

        String name = textOrNull(row.selectFirst(".village, .targetName, td.village"));
        Integer x = null, y = null;
        Element coords = row.selectFirst(".coords, .coord, td.coordinates");
        if (coords != null) {
            Matcher m = COORDS.matcher(coords.text());
            if (m.find()) {
                x = Integer.parseInt(m.group(1));
                y = Integer.parseInt(m.group(2));
            }
        }
        if (x == null || y == null) {
            // Fallback: look for <span class="coordinateX"> / <span class="coordinateY">.
            Element cx = row.selectFirst(".coordinateX");
            Element cy = row.selectFirst(".coordinateY");
            if (cx != null && cy != null) {
                Integer xi = parseCoordText(cx.text());
                Integer yi = parseCoordText(cy.text());
                if (xi != null && yi != null) { x = xi; y = yi; }
            }
        }
        if (x == null || y == null) return null;

        boolean enabled = true;
        Element markSend = row.selectFirst("input.markSend, input[type=checkbox][name*=\"slotIds\"], input.selectTarget");
        if (markSend != null) {
            enabled = markSend.hasAttr("checked") || "checked".equalsIgnoreCase(markSend.attr("checked"));
        }

        String troopsJson = extractTroopsJson(row);

        return new ParsedFarmlistTarget(targetId, name, x, y, troopsJson, enabled);
    }

    private static String extractTroopsJson(Element row) {
        Elements inputs = row.select("input[type=text], input[type=number], input[name^=troop], input[name^=t]");
        Map<String, Integer> troops = new LinkedHashMap<>();
        for (Element input : inputs) {
            String n = input.attr("name");
            Matcher m = TROOP_INPUT.matcher(n);
            if (!m.matches()) continue;
            int idx = Integer.parseInt(m.group(1));
            if (idx < 1 || idx > 11) continue;
            String raw = input.attr("value");
            Integer count = parseIntOrNull(raw);
            if (count == null || count <= 0) continue;
            troops.put("t" + idx, count);
        }
        if (troops.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : troops.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static String textOrNull(Element el) {
        if (el == null) return null;
        String t = el.text();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseCoordText(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^-0-9]", "");
        return parseIntOrNull(digits);
    }

    private static Long parseIntOrNullAsLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
