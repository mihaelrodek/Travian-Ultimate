package com.travianbot.bot.scraper;

import com.travianbot.village.domain.BuildingCatalog;
import com.travianbot.village.domain.ResourceType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Travian T4.6 village pages into domain-agnostic parse records.
 * <p>
 * The T4.6 login skin varies slightly between server generations and custom servers, so the
 * parser intentionally tries several extraction strategies in order of specificity and falls
 * back to looser class/href patterns. Regardless of skin, every tile / building link carries
 * two pieces of info we need: the slot id (1..40 via {@code ?id=NN}) and the gid+level
 * (encoded in the class list, e.g. {@code g1 level3}).
 *
 * <h3>dorf1.php — 18 resource tiles</h3>
 * Primary structure is an image map whose {@code <area>} children link to {@code /build.php?id=N}.
 * Every area's class list contains {@code gidN} (or {@code gN}) and {@code levelM} (or {@code lM}).
 *
 * <h3>dorf2.php — 22 building slots (19..40)</h3>
 * Same pattern. Slots 39 and 40 (Rally Point + Wall) are always present; empty slots have
 * gid = 0 and level = 0.
 */
public final class VillagePageParser {

    private static final Logger log = LoggerFactory.getLogger(VillagePageParser.class);

    // Matches `?id=12`, `&id=12`, `build.php?id=12` etc.
    private static final Pattern ID_PATTERN = Pattern.compile("[?&]id=(\\d+)");

    // Class tokens: `gid7`, `g7`, `level3`, `l3`. Anchored on word boundary so `gid` inside
    // something else (e.g. `dgid7X`) won't match.
    private static final Pattern GID_IN_CLASS = Pattern.compile("\\b(?:gid|g)(\\d+)\\b");
    private static final Pattern LEVEL_IN_CLASS = Pattern.compile("\\b(?:level|l)(\\d+)\\b");

    private VillagePageParser() {
    }

    /**
     * Parses a dorf1.php response. Returns exactly 18 tiles on happy path; fewer if the page
     * is malformed. Callers should check {@code result.size() == 18}.
     */
    public static List<ParsedResourceField> parseDorf1(String html) {
        Document doc = Jsoup.parse(html);
        Map<Integer, ParsedResourceField> bySlot = new HashMap<>();

        Set<Integer> underConstruction = extractUnderConstructionSlots(doc);

        // Strategy 1: image map areas (this is the standard T4.6 layout).
        Elements areas = doc.select("#rx area, map area");
        for (Element a : areas) {
            ParsedResourceField field = parseResourceTile(a, underConstruction);
            if (field != null && field.slotId() >= 1 && field.slotId() <= 18) {
                bySlot.putIfAbsent(field.slotId(), field);
            }
        }

        // Strategy 2: #resourceFieldContainer links (some custom servers).
        if (bySlot.size() < 18) {
            Elements fallback = doc.select("#resourceFieldContainer a, a.buildingSlot");
            for (Element a : fallback) {
                ParsedResourceField field = parseResourceTile(a, underConstruction);
                if (field != null && field.slotId() >= 1 && field.slotId() <= 18) {
                    bySlot.putIfAbsent(field.slotId(), field);
                }
            }
        }

        List<ParsedResourceField> result = new ArrayList<>(bySlot.values());
        result.sort((x, y) -> Integer.compare(x.slotId(), y.slotId()));
        if (result.size() != 18) {
            log.warn("dorf1 parse extracted {} of 18 resource tiles — layout may have changed", result.size());
        }
        return result;
    }

    /**
     * Parses a dorf2.php response. Returns up to 22 buildings (slots 19..40). Empty slots are
     * included with gid = 0.
     */
    public static List<ParsedBuilding> parseDorf2(String html) {
        Document doc = Jsoup.parse(html);
        Map<Integer, ParsedBuilding> bySlot = new HashMap<>();

        Set<Integer> underConstruction = extractUnderConstructionSlots(doc);

        // Strategy 1: image map areas.
        Elements areas = doc.select("#villageContent map area, #rx area, map area");
        for (Element a : areas) {
            ParsedBuilding b = parseBuildingSlot(a, underConstruction);
            if (b != null && b.slotId() >= 19 && b.slotId() <= 40) {
                bySlot.putIfAbsent(b.slotId(), b);
            }
        }

        // Strategy 2: divs with data-gid/data-level (newer skin).
        if (bySlot.size() < 10) {
            Elements slots = doc.select("#villageContent div.buildingSlot, div.buildingSlot");
            for (Element s : slots) {
                ParsedBuilding b = parseBuildingSlotDataAttrs(s, underConstruction);
                if (b != null && b.slotId() >= 19 && b.slotId() <= 40) {
                    bySlot.putIfAbsent(b.slotId(), b);
                }
            }
        }

        List<ParsedBuilding> result = new ArrayList<>(bySlot.values());
        result.sort((x, y) -> Integer.compare(x.slotId(), y.slotId()));
        if (result.size() < 10) {
            log.warn("dorf2 parse extracted only {} building slots — layout may have changed", result.size());
        }
        return result;
    }

    // ---------- tile helpers ----------

    private static ParsedResourceField parseResourceTile(Element el, Set<Integer> underConstruction) {
        Integer slot = extractSlotId(el);
        if (slot == null) return null;

        int gid = extractGid(el);
        int level = extractLevel(el);
        ResourceType type = ResourceType.fromGid(gid);
        if (type == null) {
            // Try to recover from the `gidN` in an alt/title attribute
            String alt = firstNonNull(el.attr("alt"), el.attr("title"));
            if (alt != null) {
                Matcher m = GID_IN_CLASS.matcher(alt);
                if (m.find()) {
                    gid = Integer.parseInt(m.group(1));
                    type = ResourceType.fromGid(gid);
                }
            }
        }
        if (type == null) return null; // Not a resource tile.

        return new ParsedResourceField(slot, gid, type, level, underConstruction.contains(slot));
    }

    private static ParsedBuilding parseBuildingSlot(Element el, Set<Integer> underConstruction) {
        Integer slot = extractSlotId(el);
        if (slot == null) return null;
        int gid = extractGid(el);
        int level = extractLevel(el);
        String name = gid == 0 ? null : BuildingCatalog.nameFor(gid);
        return new ParsedBuilding(slot, gid, name, level, underConstruction.contains(slot));
    }

    private static ParsedBuilding parseBuildingSlotDataAttrs(Element el, Set<Integer> underConstruction) {
        Integer slot = parseIntOrNull(el.attr("data-aid"));
        if (slot == null) slot = parseIntOrNull(el.attr("data-id"));
        if (slot == null) slot = extractSlotId(el);
        if (slot == null) return null;

        int gid = firstInt(
                parseIntOrNull(el.attr("data-gid")),
                extractGid(el),
                0
        );
        int level = firstInt(
                parseIntOrNull(el.attr("data-level")),
                extractLevel(el),
                0
        );
        String name = gid == 0 ? null : BuildingCatalog.nameFor(gid);
        return new ParsedBuilding(slot, gid, name, level, underConstruction.contains(slot));
    }

    // ---------- small helpers ----------

    private static Integer extractSlotId(Element el) {
        String href = el.attr("href");
        if (href != null && !href.isBlank()) {
            Matcher m = ID_PATTERN.matcher(href);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        String onclick = el.attr("onclick");
        if (onclick != null && !onclick.isBlank()) {
            Matcher m = ID_PATTERN.matcher(onclick);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        // class="buildingSlot a12" (some skins encode slot id as aN in the class list)
        String cls = el.className();
        Matcher m = Pattern.compile("\\ba(\\d+)\\b").matcher(cls);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    private static int extractGid(Element el) {
        String cls = el.className();
        Matcher m = GID_IN_CLASS.matcher(cls);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    private static int extractLevel(Element el) {
        String cls = el.className();
        Matcher m = LEVEL_IN_CLASS.matcher(cls);
        if (m.find()) return Integer.parseInt(m.group(1));
        // `<div class="labelLayer">5</div>` inside the element
        Element label = el.selectFirst(".labelLayer, .level");
        if (label != null) {
            String txt = label.ownText();
            if (txt != null) {
                Integer n = parseIntOrNull(txt.trim());
                if (n != null) return n;
            }
        }
        return 0;
    }

    /**
     * Finds all slot ids that show a construction/upgrade indicator. T4.6 marks these with
     * class {@code underConstruction} or an adjacent {@code .buildDuration} countdown.
     */
    private static Set<Integer> extractUnderConstructionSlots(Document doc) {
        Set<Integer> out = new HashSet<>();
        Elements els = doc.select(".underConstruction, .buildingSlot.underConstruction, .level.underConstruction");
        for (Element el : els) {
            Integer slot = extractSlotId(el);
            if (slot != null) out.add(slot);
        }
        return out;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int firstInt(Integer a, Integer b, int fallback) {
        if (a != null && a != 0) return a;
        if (b != null && b != 0) return b;
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }

    private static String firstNonNull(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
