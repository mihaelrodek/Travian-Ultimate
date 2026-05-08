package com.travianbot.hero.scraper;

import com.travianbot.hero.domain.HeroProductionType;
import com.travianbot.hero.domain.HeroStatus;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code /hero/attributes} page. T4.6 Legends renders:
 * <ul>
 *   <li>XP as a pair inside {@code .experience .points} → "12 345 / 50 000"</li>
 *   <li>Health as a percentage inside {@code .health .value} → "58%"</li>
 *   <li>Free skill points inside {@code .freePoints .value} → "3"</li>
 *   <li>Per-attribute spent points via a hidden {@code input[name=...]} for
 *       the form POST, plus a visible read-only span with
 *       {@code data-attribute="fightingStrength"}.</li>
 * </ul>
 * Older skins used different class names, and the page occasionally renders
 * the numbers as plain "Experience: 12345 / 50000" text. Every lookup below
 * therefore cascades:
 * <ol>
 *   <li>A skin-specific selector,</li>
 *   <li>a more generic selector,</li>
 *   <li>a text-scan fallback that looks for a label keyword and pulls the
 *       first number after it.</li>
 * </ol>
 * Every field is nullable — fresher-than-stale beats all-or-nothing.
 */
public final class HeroAttributesParser {

    private static final Pattern NEWDID = Pattern.compile("[?&]newdid=(\\d+)");
    /** Two numbers separated by "/" — used for XP and the free-points line. */
    private static final Pattern NUM_PAIR =
            Pattern.compile("(-?\\d[\\d.,\\s]*)\\s*/\\s*(-?\\d[\\d.,\\s]*)");
    /** Any signed integer. */
    private static final Pattern INT = Pattern.compile("(-?\\d+)");

    private HeroAttributesParser() {
    }

    public static ParsedHeroAttributes parse(String html) {
        Document doc = Jsoup.parse(html);

        String name = textOrNull(doc, "#heroName, .heroName, .hero .name, .heroProfile .name");

        Integer level = parseLevel(doc);

        // Experience is always "current / next" or just "current".
        long[] xpPair = parseXpPair(doc);
        Long experience = xpPair == null ? null : xpPair[0];
        Long experienceForNext = xpPair == null ? null : (xpPair[1] > 0 ? xpPair[1] : null);

        Integer health = parseHealth(doc);
        if (health != null) health = Math.min(100, Math.max(0, health));

        Integer regen = parsePercentOrInt(firstAttrOrText(doc,
                ".heroRegeneration, .regeneration, .regen",
                "regeneration", "regeneration"));

        Integer freePts = parseFreePoints(doc);

        Integer fighting  = readPointSpent(doc, "fightingStrength", "fighting strength", "kampfkraft", "attack");
        Integer offBonus  = readPointSpent(doc, "offBonus", "off bonus", "offensive bonus", "angriffsbonus", "off.-bonus");
        Integer defBonus  = readPointSpent(doc, "defBonus", "def bonus", "defence bonus", "defensive bonus", "verteidigungsbonus", "def.-bonus");
        Integer resources = readPointSpent(doc, "resources", "resources", "rohstoffe", "resource production");

        HeroStatus status = parseStatus(doc);
        HeroProductionType production = parseProductionType(doc);

        Long homeVillageDid = parseHomeVillageDid(doc);
        Long currentVillageDid = parseCurrentVillageDid(doc);

        Instant reviveAt = parseReviveAt(doc);

        return new ParsedHeroAttributes(
                name, level, experience, experienceForNext, health, regen,
                freePts, fighting, offBonus, defBonus, resources,
                status, production, homeVillageDid, currentVillageDid, reviveAt
        );
    }

    private static String textOrNull(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        if (el == null) return null;
        String t = el.text();
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    /**
     * Level can appear as "Level 14", "Stufe 14", or as a bare number inside
     * {@code .heroLevel}. We try the CSS selector first, then scan the page
     * text.
     */
    private static Integer parseLevel(Document doc) {
        Element el = doc.selectFirst(
                ".heroLevel, .hero .level, .heroProfile .level, "
                        + "[class~=level] .value, [data-level]"
        );
        if (el != null) {
            String dv = firstNonBlank(el.attr("data-level"), el.attr("data-value"), el.text());
            Integer v = parseIntOrNull(dv);
            if (v != null) return v;
        }
        String body = doc.text();
        for (String label : new String[]{"Level", "Stufe", "Niveau"}) {
            Matcher m = Pattern.compile(Pattern.quote(label) + "\\s*[:]?\\s*(\\d{1,3})",
                    Pattern.CASE_INSENSITIVE).matcher(body);
            if (m.find()) return Integer.valueOf(m.group(1));
        }
        return null;
    }

    /**
     * Tries a CSS selector first; if absent, falls back to the first text
     * snippet that matches one of the locale-aware labels. Pulls the value
     * between the label and the end of the sentence.
     */
    private static String firstAttrOrText(Document doc, String selector, String... labels) {
        Element el = doc.selectFirst(selector);
        if (el != null) {
            // Many skins stash the value in data-value / data-points for JS
            // hydration. Prefer the attribute — the text often contains the
            // label and a " /max" suffix we'd have to split anyway.
            String dv = firstNonBlank(el.attr("data-value"), el.attr("data-points"));
            if (dv != null) return dv;
            // Sometimes the value lives in a nested .value span.
            Element val = el.selectFirst(".value, .points, span.number");
            if (val != null) {
                String inner = firstNonBlank(val.attr("data-value"), val.text());
                if (inner != null) return inner;
            }
            return el.text();
        }
        String body = doc.text().toLowerCase(Locale.ROOT);
        for (String label : labels) {
            int i = body.indexOf(label.toLowerCase(Locale.ROOT));
            if (i < 0) continue;
            int start = i + label.length();
            // Skip ": " / ":" / whitespace up to 40 chars away.
            while (start < body.length() && !Character.isDigit(body.charAt(start))
                    && body.charAt(start) != '-') {
                start++;
                if (start - i > 40) break;
            }
            if (start >= body.length()) continue;
            int end = start;
            while (end < body.length() && (Character.isDigit(body.charAt(end))
                    || body.charAt(end) == '.' || body.charAt(end) == ',' || body.charAt(end) == '/'
                    || body.charAt(end) == '%')) {
                end++;
            }
            if (end > start) return body.substring(start, end).trim();
        }
        return null;
    }

    /**
     * Parses the XP pair ({@code current / nextLevel}). The pair is almost
     * always shown textually; we accept thousands separators (space, dot, or
     * comma) because the server locale varies.
     * <p>
     * Like {@link #parseHealth}, the top-bar SVG title ({@code #topBarHero svg title})
     * is the most reliable source. We strip Unicode bidi marks first —
     * Travian wraps numbers in LRE/PDF (U+202D / U+202C) in some locales.
     */
    private static long[] parseXpPair(Document doc) {
        // Prefer the top-bar SVG title — same logic as parseHealth.
        String topBar = topBarTitleText(doc, "topBarHero");
        if (topBar != null) {
            Matcher pair = NUM_PAIR.matcher(topBar);
            if (pair.find()) {
                Long first = digitsOnly(pair.group(1));
                Long second = digitsOnly(pair.group(2));
                if (first != null) return new long[]{first, second == null ? 0L : second};
            }
            Long only = digitsOnly(topBar);
            if (only != null) return new long[]{only, 0L};
        }

        // Preferred T4.6 container; fall back to legacy.
        Element el = doc.selectFirst(
                ".experience .points, .heroExperience .points, "
                        + ".experience .value, .heroExperience .value, "
                        + ".heroExperience, .experience, .exp, "
                        + "[data-experience]"
        );
        String raw = null;
        if (el != null) {
            raw = firstNonBlank(
                    el.attr("data-experience"),
                    el.attr("data-value"),
                    el.text()
            );
        }
        if (raw == null || raw.isBlank()) {
            // Text scan around the "experience" label.
            raw = labelSnippet(doc, 80, "experience", "erfahrung");
        }
        if (raw == null) return null;

        Matcher pair = NUM_PAIR.matcher(raw);
        if (pair.find()) {
            Long first = digitsOnly(pair.group(1));
            Long second = digitsOnly(pair.group(2));
            if (first != null) return new long[]{first, second == null ? 0L : second};
        }
        Long only = digitsOnly(raw);
        if (only != null) return new long[]{only, 0L};
        return null;
    }

    /**
     * Health is usually a percentage in T4.6 ("58%" inside a pill), but older
     * skins render an absolute integer, and the newest skin sometimes carries
     * the number in a {@code style="width: 58%"} on a bar element.
     * <p>
     * The top-bar hero gauge ({@code #topBarHeroWrapper svg title}) is the most
     * reliable source — it's present on every page, skin-independent, and shows
     * {@code "Health: 68%"}. Travian wraps the number in Unicode bidi marks
     * (LRE {@code U+202D} / PDF {@code U+202C}) to force LTR rendering inside
     * Arabic/Hebrew skins — we strip those before parsing so digits appear
     * contiguous to {@link #parsePercentOrInt}.
     */
    private static Integer parseHealth(Document doc) {
        // Prefer the top-bar SVG title — reliable across skins and present on every page.
        String topBar = topBarTitleText(doc, "topBarHeroWrapper");
        if (topBar != null) {
            Integer v = parsePercentOrInt(topBar);
            if (v != null) return Math.min(100, Math.max(0, v));
        }

        Element el = doc.selectFirst(
                ".health .value, .heroHealth .value, "
                        + ".health .points, .heroHealth .points, "
                        + ".heroHealth, .health, [data-health]"
        );
        if (el != null) {
            String dv = firstNonBlank(el.attr("data-health"), el.attr("data-value"));
            Integer v = parsePercentOrInt(dv);
            if (v != null) return v;
            v = parsePercentOrInt(el.text());
            if (v != null) return v;
            // Some skins only paint the bar width — use it as a percentage.
            Element bar = el.selectFirst("[style*=width]");
            if (bar != null) {
                Matcher m = Pattern.compile("width\\s*:\\s*(\\d+)").matcher(bar.attr("style"));
                if (m.find()) return Integer.valueOf(m.group(1));
            }
        }
        return parsePercentOrInt(labelSnippet(doc, 20, "health", "gesundheit"));
    }

    /**
     * Free points = skill points not yet spent. Shown as a single integer
     * inside a badge. The badge class varies; try the common ones and fall
     * back to a label scan.
     */
    private static Integer parseFreePoints(Document doc) {
        Element el = doc.selectFirst(
                ".freePoints .value, .freePoints .points, "
                        + "span.freePoints, .freePoints, "
                        + "[id*=freePoints], [data-free-points]"
        );
        if (el != null) {
            String dv = firstNonBlank(el.attr("data-free-points"), el.attr("data-value"));
            Integer v = parseIntOrNull(dv);
            if (v != null) return v;
            return parseIntOrNull(el.text());
        }
        // Text fallback.
        return parseIntOrNull(labelSnippet(doc, 20,
                "unused", "available", "free points", "frei verfügbar", "frei"));
    }

    private static Integer parsePercentOrInt(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String raw) {
        return parsePercentOrInt(raw);
    }

    private static Long digitsOnly(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("[^0-9-]", "");
        if (d.isEmpty() || d.equals("-")) return null;
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads the currently-spent point count for one attribute. The canonical
     * source is the hidden form input (the submit button POSTs these), but
     * the input is only present when the edit panel is expanded. We therefore
     * try, in order:
     * <ol>
     *   <li>the form input by name,</li>
     *   <li>a visible {@code data-value} on a read-only span
     *       ({@code span[data-attribute=fightingStrength]}),</li>
     *   <li>a class-based container with a {@code .value} child,</li>
     *   <li>a label-based text scan.</li>
     * </ol>
     */
    private static Integer readPointSpent(Document doc, String keyword, String... labels) {
        // 1) Hidden form input — most authoritative when the attribute form is visible.
        for (Element input : List.copyOf(doc.select(
                "input[name=" + keyword + "], "
                        + "input[name*=" + keyword + "], "
                        + "input[id=" + keyword + "], "
                        + "input[id*=" + keyword + "]"))) {
            String v = input.attr("value");
            Integer vi = parseIntOrNull(v);
            if (vi != null) return vi;
        }
        // 2) Visible read-only span with a data-attribute marker.
        for (Element span : List.copyOf(doc.select(
                "[data-attribute=" + keyword + "], "
                        + "[data-attr=" + keyword + "], "
                        + "[data-stat=" + keyword + "]"))) {
            String dv = firstNonBlank(span.attr("data-value"), span.attr("data-points"), span.text());
            Integer v = parseIntOrNull(dv);
            if (v != null) return v;
        }
        // 3) Class-based container (e.g. .attribute.fightingStrength .value).
        Element container = doc.selectFirst(
                ".attribute." + keyword + " .value, "
                        + ".attribute." + keyword + " .points, "
                        + "." + keyword + " .value, "
                        + "." + keyword + " .points, "
                        + "[class*=" + keyword + "] .value"
        );
        if (container != null) {
            String dv = firstNonBlank(container.attr("data-value"), container.text());
            Integer v = parseIntOrNull(dv);
            if (v != null) return v;
        }
        // 4) Text-scan fallback — last resort.
        return parseIntOrNull(labelSnippet(doc, 30, labels));
    }

    /**
     * Finds the first label in the document text and returns a short snippet
     * that starts at the first digit following the label. Used as the last
     * resort in all three parsers.
     */
    private static String labelSnippet(Document doc, int maxLookahead, String... labels) {
        String body = doc.text().toLowerCase(Locale.ROOT);
        for (String label : labels) {
            int i = body.indexOf(label.toLowerCase(Locale.ROOT));
            if (i < 0) continue;
            int start = i + label.length();
            while (start < body.length() && !Character.isDigit(body.charAt(start))
                    && body.charAt(start) != '-') {
                start++;
                if (start - i > maxLookahead) break;
            }
            if (start >= body.length() || start - i > maxLookahead) continue;
            int end = start;
            while (end < body.length() && (Character.isDigit(body.charAt(end))
                    || body.charAt(end) == '.' || body.charAt(end) == ',' || body.charAt(end) == '/'
                    || body.charAt(end) == '%' || body.charAt(end) == ' ')) {
                end++;
            }
            if (end > start) return body.substring(start, end).trim();
        }
        return null;
    }

    private static HeroStatus parseStatus(Document doc) {
        Elements tags = doc.select(".heroStatus, .status, .hero .status, [data-hero-status]");
        String raw = tags.isEmpty()
                ? (doc.selectFirst("body") != null ? doc.body().className() : "")
                : tags.text();
        if (raw == null) raw = "";
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("dead") || s.contains("gestorben") || s.contains("tot")) return HeroStatus.DEAD;
        if (s.contains("reviv") || s.contains("wiederbelebung")) return HeroStatus.REVIVING;
        if (s.contains("adventure") || s.contains("abenteuer")) return HeroStatus.IN_ADVENTURE;
        if (s.contains("moving") || s.contains("unterwegs") || s.contains("travel")) return HeroStatus.MOVING;
        if (s.contains("home") || s.contains("zuhause") || s.contains("village")) return HeroStatus.HOME;
        return HeroStatus.UNKNOWN;
    }

    private static HeroProductionType parseProductionType(Document doc) {
        Element selected = doc.selectFirst(
                "input[name=productionType][checked], "
                        + "input[name=productionType]:checked, "
                        + "input[name=tt][checked], "
                        + "input[name=tt]:checked, "
                        + ".heroProduction input[checked]"
        );
        if (selected != null) {
            String v = selected.attr("value");
            if (v == null) v = "";
            return switch (v) {
                case "1" -> HeroProductionType.WOOD;
                case "2" -> HeroProductionType.CLAY;
                case "3" -> HeroProductionType.IRON;
                case "4" -> HeroProductionType.CROP;
                case "0" -> HeroProductionType.ALL_RESOURCES;
                default -> HeroProductionType.UNKNOWN;
            };
        }
        String body = doc.text().toLowerCase(Locale.ROOT);
        if (body.contains("all resources") || body.contains("alle rohstoffe")) return HeroProductionType.ALL_RESOURCES;
        if (body.contains("wood") || body.contains("holz")) return HeroProductionType.WOOD;
        if (body.contains("clay") || body.contains("lehm")) return HeroProductionType.CLAY;
        if (body.contains("iron") || body.contains("eisen")) return HeroProductionType.IRON;
        if (body.contains("crop") || body.contains("getreide")) return HeroProductionType.CROP;
        return HeroProductionType.UNKNOWN;
    }

    private static Long parseHomeVillageDid(Document doc) {
        Element a = doc.selectFirst(".heroHome a, .home a, a[class*=home]");
        if (a == null) return null;
        Matcher m = NEWDID.matcher(a.attr("href"));
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static Long parseCurrentVillageDid(Document doc) {
        Element a = doc.selectFirst(".heroVillage a, .currentVillage a");
        if (a == null) return null;
        Matcher m = NEWDID.matcher(a.attr("href"));
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static Instant parseReviveAt(Document doc) {
        Element t = doc.selectFirst(".heroRevive span.timer, .reviveTimer, [class*=revive] .timer");
        if (t == null) return null;
        String val = firstNonBlank(t.attr("value"), t.attr("data-end"), t.attr("data-remaining"));
        if (val != null && !val.isBlank()) {
            try {
                long seconds = Long.parseLong(val.replaceAll("[^0-9]", ""));
                if (seconds > 0) return Instant.now().plusSeconds(seconds);
            } catch (NumberFormatException ignored) {
                // fall through to absolute-time parse
            }
        }
        String raw = t.text().trim();
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Pulls the text out of an SVG {@code <title>} nested inside {@code #<wrapperId>}
     * on the Travian top bar. Returns the bidi-stripped title string, or null if
     * the element is missing or blank.
     * <p>
     * The top bar renders two SVG gauges:
     * <ul>
     *   <li>{@code #topBarHeroWrapper} — health donut, title "Health: 68%"</li>
     *   <li>{@code #topBarHero} — XP donut, title like "Experience: 12345 / 50000"</li>
     * </ul>
     * Both are present on <b>every</b> in-game page, which makes this the most
     * reliable source for hero health/XP regardless of which page we scraped.
     */
    private static String topBarTitleText(Document doc, String wrapperId) {
        // jsoup lowercases tag names when parsing HTML, so "title" is safe.
        Element t = doc.selectFirst("#" + wrapperId + " svg title");
        if (t == null) t = doc.selectFirst("#" + wrapperId + " title");
        if (t == null) return null;
        String raw = t.text();
        if (raw == null || raw.isBlank()) return null;
        return stripBidiMarks(raw).trim();
    }

    /**
     * Strips Unicode bidi-control marks that Travian embeds around numbers to
     * force LTR rendering inside RTL skins (Arabic, Hebrew). The marks are
     * invisible but would otherwise leak into {@code %} / number parsing.
     * Covers the common ones: LRM/RLM ({@code U+200E/F}), LRE/RLE/PDF/LRO/RLO
     * ({@code U+202A-E}), and ALM ({@code U+061C}).
     */
    private static String stripBidiMarks(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u202A-\\u202E\\u200E\\u200F\\u061C]", "");
    }
}
