package com.travianbot.bot.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts identity info for the currently active village from dorf1/dorf2 HTML.
 * <p>
 * The "active" village is whichever one Travian has selected in the village-switcher. We pull
 * its in-game id from the URL attribute of the active switcher link and the name/coords from
 * the village header block.
 */
public final class VillageHeaderParser {

    private static final Pattern NEWDID_PATTERN = Pattern.compile("[?&]newdid=(\\d+)");
    private static final Pattern COORDS_PATTERN = Pattern.compile("\\(\\s*(-?\\d+)\\s*[|,/]\\s*(-?\\d+)\\s*\\)");

    private VillageHeaderParser() {
    }

    public static ParsedVillageHeader parse(String html) {
        Document doc = Jsoup.parse(html);

        Long travianId = extractActiveTravianId(doc);
        String name = extractName(doc);
        int[] coords = extractCoords(doc);

        Integer x = coords != null ? coords[0] : null;
        Integer y = coords != null ? coords[1] : null;
        return new ParsedVillageHeader(travianId, name, x, y);
    }

    private static Long extractActiveTravianId(Document doc) {
        // T4.6 right-column village switcher uses class `villageList` with the active one
        // marked `active`. Each row has an `a href="?newdid=123..."`.
        String[] activeSelectors = {
                "#sidebarBoxVillagelist .listEntry.active a",
                "#sidebarBoxVillagelist a.active",
                ".villageList .active a",
                ".villageList a.active",
                "a.active[href*=newdid]"
        };
        for (String sel : activeSelectors) {
            Element el = doc.selectFirst(sel);
            if (el == null) continue;
            String href = el.attr("href");
            Matcher m = NEWDID_PATTERN.matcher(href);
            if (m.find()) return Long.parseLong(m.group(1));
        }
        // Fallback: look for `data-did` on an active wrapper.
        Element wrapper = doc.selectFirst("[data-did][data-active=\"true\"]");
        if (wrapper != null) {
            String did = wrapper.attr("data-did");
            try {
                return Long.parseLong(did);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static String extractName(Document doc) {
        String[] nameSelectors = {
                "#villageNameField",
                "#villageName",
                ".villageInput",
                "h1.titleInHeader",
                "#content h1"
        };
        for (String sel : nameSelectors) {
            Element el = doc.selectFirst(sel);
            if (el == null) continue;
            String txt = el.text();
            if (txt != null && !txt.isBlank()) return txt.trim();
        }
        return null;
    }

    private static int[] extractCoords(Document doc) {
        // T4.6 has `<span class="coordinateX">(42)</span><span class="coordinateY">(-13)</span>`
        Element x = doc.selectFirst(".coordinateX, #coordX");
        Element y = doc.selectFirst(".coordinateY, #coordY");
        Integer xi = toInt(x);
        Integer yi = toInt(y);
        if (xi != null && yi != null) return new int[]{xi, yi};

        // Fallback: parse "(X|Y)" anywhere in the header text.
        Element header = doc.selectFirst("#villageNameField, h1.titleInHeader, #coordinatesWrapper");
        String raw = header != null ? header.text() : doc.select("body").text();
        if (raw != null) {
            Matcher m = COORDS_PATTERN.matcher(raw);
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            }
        }
        return null;
    }

    private static Integer toInt(Element el) {
        if (el == null) return null;
        String txt = el.text();
        if (txt == null) return null;
        // Strip parens / signs
        String cleaned = txt.replaceAll("[^-0-9]", "");
        if (cleaned.isBlank() || cleaned.equals("-")) return null;
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
