package com.travianbot.bot.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the right-hand "my villages" switcher ({@code #sidebarBoxVillagelist}) on any
 * dorf1/dorf2/building page. Returns one {@link ParsedSidebarVillage} per entry.
 * <p>
 * T4.6 Legends renders each row as
 * {@code <li class="listEntry village [active]" data-did="123"> <a href="?newdid=123"> <span class="name">...</span> <span class="coordinates">...</span> </a> </li>}
 * and optionally groups them with either a group header ({@code <h4 class="groupName">Capital</h4>}
 * before a batch of rows) or a per-row attribute ({@code data-group="Capital"}).
 * <p>
 * We handle both shapes: if a row carries {@code data-group}, that's the authoritative value;
 * otherwise we walk back to the most recent preceding group header in DOM order. Unknown-group
 * entries come out with {@code groupName = null}, which the village upsert treats as
 * "ungrouped".
 */
public final class SidebarVillageListParser {

    private static final Logger log = LoggerFactory.getLogger(SidebarVillageListParser.class);

    private static final Pattern NEWDID_PATTERN = Pattern.compile("[?&]newdid=(\\d+)");
    private static final Pattern COORDS_PATTERN = Pattern.compile("\\(\\s*(-?\\d+)\\s*[|,/]\\s*(-?\\d+)\\s*\\)");

    private SidebarVillageListParser() {
    }

    public static List<ParsedSidebarVillage> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<ParsedSidebarVillage> out = new ArrayList<>();

        Element box = doc.selectFirst("#sidebarBoxVillagelist, .villageList");
        if (box == null) {
            // No sidebar on some endpoint pages — quiet log, not an error.
            log.debug("No #sidebarBoxVillagelist found in page");
            return out;
        }

        // Grab rows. We accept several shapes:
        //   <li class="listEntry ...">
        //   <div class="listEntry ...">
        //   <a class="listEntry ...">  (older skins)
        Elements rows = box.select(".listEntry, li.village, li[data-did]");
        if (rows.isEmpty()) {
            // Really old skins wrap each entry in an <a> with no container li.
            rows = box.select("a[href*=newdid]");
        }

        // Group header state — updated as we walk the DOM. Reset to null when we hit one.
        String currentGroup = null;
        for (Element row : rows) {
            // If this row is preceded by a group header immediately in the DOM, promote it.
            // We check `previousElementSibling` because some skins intersperse group headers
            // directly between rows rather than wrapping each group in its own container.
            Element prev = row.previousElementSibling();
            while (prev != null && isGroupHeader(prev)) {
                currentGroup = textOrNull(prev);
                prev = prev.previousElementSibling();
            }

            Long did = extractDid(row);
            if (did == null) continue; // not a village row

            String name = extractName(row);
            int[] coords = extractCoords(row);
            String group = extractGroupAttr(row);
            if (group == null) group = currentGroup;
            boolean active = row.hasClass("active")
                    || row.selectFirst(".active") != null
                    || "true".equalsIgnoreCase(row.attr("data-active"));

            out.add(new ParsedSidebarVillage(
                    did,
                    name,
                    coords != null ? coords[0] : null,
                    coords != null ? coords[1] : null,
                    group,
                    active
            ));
        }
        return out;
    }

    private static boolean isGroupHeader(Element el) {
        if (el == null) return false;
        String tag = el.tagName();
        if (("h3".equals(tag) || "h4".equals(tag) || "h5".equals(tag))
                && (el.hasClass("groupName") || el.hasClass("villageGroup"))) {
            return true;
        }
        return el.hasClass("groupName") || el.hasClass("villageGroup");
    }

    private static Long extractDid(Element row) {
        String attrDid = row.attr("data-did");
        if (!attrDid.isBlank()) {
            try { return Long.parseLong(attrDid.trim()); } catch (NumberFormatException ignored) {}
        }
        Element anchor = "a".equals(row.tagName()) ? row : row.selectFirst("a[href*=newdid]");
        if (anchor != null) {
            Matcher m = NEWDID_PATTERN.matcher(anchor.attr("href"));
            if (m.find()) return Long.parseLong(m.group(1));
        }
        return null;
    }

    private static String extractName(Element row) {
        // Prefer explicit name span; fall back to anchor text with coords stripped.
        Element nameEl = row.selectFirst(".name, .villageName");
        if (nameEl != null) {
            String t = textOrNull(nameEl);
            if (t != null) return t;
        }
        Element anchor = "a".equals(row.tagName()) ? row : row.selectFirst("a[href*=newdid]");
        if (anchor != null) {
            String t = anchor.text();
            if (t == null) return null;
            // Strip "(x|y)" trailing coordinates so "Srdan Mladan`s village (42|-13)" → name only.
            t = COORDS_PATTERN.matcher(t).replaceAll("").trim();
            return t.isBlank() ? null : t;
        }
        return null;
    }

    private static int[] extractCoords(Element row) {
        Element coordsEl = row.selectFirst(".coordinates, .coordinatesWrapper");
        String raw = coordsEl != null ? coordsEl.text() : row.text();
        if (raw == null) return null;
        Matcher m = COORDS_PATTERN.matcher(raw);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    private static String extractGroupAttr(Element row) {
        String attr = row.attr("data-group");
        if (!attr.isBlank()) return attr.trim();
        Element groupEl = row.selectFirst(".group, .groupName");
        if (groupEl != null) return textOrNull(groupEl);
        return null;
    }

    private static String textOrNull(Element el) {
        if (el == null) return null;
        String t = el.text();
        return (t == null || t.isBlank()) ? null : t.trim();
    }
}
