package com.travianbot.intel.alliance.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Jsoup parser for the in-game alliance detail page
 * ({@code /allianz.php?aid=N} or the newer {@code /alliance/N}).
 * <p>
 * Travian skins this page differently across server versions and locales, so
 * the parser intentionally tries several DOM shapes for each section and
 * never throws — missing pieces come back as empty / null instead of a
 * parse failure. The contract is "we extracted what we could find".
 * <p>
 * Three sections:
 * <ol>
 *   <li><b>Description</b> — a free-text block from {@code .description},
 *       {@code #allianceDescription}, or a sibling of the alliance header.</li>
 *   <li><b>Members</b> — the roster table. Player uid is read from the
 *       {@code href} on the player-name cell, supporting both the legacy
 *       {@code ?uid=N} pattern and the newer {@code /profile/N} path.</li>
 *   <li><b>Bonuses</b> — the four standard alliance bonuses (diplomacy,
 *       mining, scout, brewery). Localized labels are normalised by a small
 *       lookup table; anything we don't recognise still lands in
 *       {@link ParsedAllianceDetail#rawBonusesJson} so we don't lose data.</li>
 * </ol>
 */
public final class AllianceDetailParser {

    /**
     * Localised bonus labels — lowercase. Keys are stripped of trailing colons
     * / whitespace before lookup. Maps to the canonical bonus name we use as
     * a JSON key in {@link ParsedAllianceDetail#rawBonusesJson} and as the
     * column on {@code map_alliance}. Add more locales here as we encounter
     * them in the wild.
     */
    private static final Map<String, String> BONUS_LABELS = Map.ofEntries(
            // diplomacy
            Map.entry("diplomacy", "diplomacy"),
            Map.entry("diplomatie", "diplomacy"),
            Map.entry("diplomatija", "diplomacy"),
            Map.entry("diplomácia", "diplomacy"),
            // mining
            Map.entry("mining", "mining"),
            Map.entry("bergbau", "mining"),
            Map.entry("rudarstvo", "mining"),
            Map.entry("těžba", "mining"),
            // scout
            Map.entry("scout", "scout"),
            Map.entry("scouting", "scout"),
            Map.entry("spähen", "scout"),
            Map.entry("kundschafter", "scout"),
            Map.entry("izviđanje", "scout"),
            // brewery
            Map.entry("brewery", "brewery"),
            Map.entry("brauerei", "brewery"),
            Map.entry("pivovara", "brewery"),
            Map.entry("pivovar", "brewery")
    );

    /** uid extractor for both {@code profile/123} and {@code spieler.php?uid=123} link styles. */
    private static final Pattern UID_LINK_PATTERN =
            Pattern.compile("(?:[?&]uid=|/profile/|/spieler/)(\\d+)", Pattern.CASE_INSENSITIVE);

    /** Pull a level number out of strings like "Level 3", "Stufe 5", "Lv 2", or just "3". */
    private static final Pattern LEVEL_PATTERN =
            Pattern.compile("(\\d{1,2})");

    private AllianceDetailParser() {
        // static-only
    }

    public static ParsedAllianceDetail parse(String html) {
        if (html == null || html.isBlank()) {
            return new ParsedAllianceDetail("", List.of(), null, null, null, null, "{}");
        }
        Document doc = Jsoup.parse(html);

        String description = parseDescription(doc);
        List<ParsedAllianceMember> members = parseMembers(doc);
        Map<String, Integer> bonuses = parseBonuses(doc);

        Integer diplomacy = bonuses.get("diplomacy");
        Integer mining = bonuses.get("mining");
        Integer scout = bonuses.get("scout");
        Integer brewery = bonuses.get("brewery");
        String json = toJson(bonuses);

        return new ParsedAllianceDetail(description, members, diplomacy, mining, scout, brewery, json);
    }

    // ---------- Description --------------------------------------------------

    /**
     * The description block lives under one of several class/id combinations
     * depending on skin. We try the well-known selectors in order and fall
     * back to the empty string when none hit — better to render an empty
     * description than misattribute random page text.
     */
    private static String parseDescription(Document doc) {
        String[] selectors = {
                "#allianceDescription",
                ".allianceDescription",
                "#detail .description",
                ".profileDescription",
                ".description"
        };
        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                String text = el.text();
                if (text != null && !text.isBlank()) return text.trim();
            }
        }
        return "";
    }

    // ---------- Members ------------------------------------------------------

    /**
     * Walk every {@code <table>} in the document looking for one whose header
     * row labels suggest a roster table. We don't anchor on a specific class
     * because the class name varies by skin — header-text matching is more
     * portable.
     */
    private static List<ParsedAllianceMember> parseMembers(Document doc) {
        List<ParsedAllianceMember> out = new ArrayList<>();

        // Prefer obvious selectors first.
        Elements tables = doc.select("table.members, table.memberlist, table#memberList, table");
        for (Element table : tables) {
            // Identify column indices from the header row.
            ColumnLayout layout = detectColumns(table);
            if (layout == null) continue;

            Elements rows = table.select("tbody tr");
            if (rows.isEmpty()) rows = table.select("tr");
            for (Element tr : rows) {
                if (!tr.select("th").isEmpty() && tr.select("td").isEmpty()) continue; // header row
                Elements cells = tr.select("td");
                if (cells.isEmpty()) continue;
                ParsedAllianceMember member = parseMemberRow(cells, layout);
                if (member != null) out.add(member);
            }

            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static ParsedAllianceMember parseMemberRow(Elements cells, ColumnLayout layout) {
        if (layout.nameIdx >= cells.size()) return null;
        Element nameCell = cells.get(layout.nameIdx);

        Long uid = extractUidFromLink(nameCell);
        String name = nameCell.text().trim();
        if (name.isEmpty()) return null;
        if (uid == null) return null;

        Integer population = null;
        if (layout.popIdx >= 0 && layout.popIdx < cells.size()) {
            population = parseFirstInt(cells.get(layout.popIdx).text());
        }
        if (population == null) population = 0;

        String role = null;
        if (layout.roleIdx >= 0 && layout.roleIdx < cells.size()) {
            String r = cells.get(layout.roleIdx).text();
            if (r != null && !r.isBlank()) role = r.trim();
        }
        return new ParsedAllianceMember(uid, name, population, role);
    }

    /**
     * Walk the table's header cells and figure out which column holds what.
     * Returns null if we can't find a player-name column — that's our signal
     * that this table isn't a roster (could be a bonuses or stats table).
     */
    private static ColumnLayout detectColumns(Element table) {
        Elements headerCells = table.select("thead th");
        if (headerCells.isEmpty()) headerCells = table.select("tr").first() == null
                ? new Elements()
                : table.select("tr").first().select("th, td");
        if (headerCells.isEmpty()) return null;

        int nameIdx = -1, popIdx = -1, roleIdx = -1;
        for (int i = 0; i < headerCells.size(); i++) {
            String h = headerCells.get(i).text().trim().toLowerCase(Locale.ROOT);
            if (h.isEmpty()) continue;
            if (nameIdx < 0 && (h.equals("player") || h.equals("name") || h.contains("spieler")
                    || h.contains("hráč") || h.contains("igrač"))) {
                nameIdx = i;
            } else if (popIdx < 0 && (h.equals("population") || h.contains("pop") || h.contains("einw")
                    || h.contains("stanovništv") || h.contains("obyvatel"))) {
                popIdx = i;
            } else if (roleIdx < 0 && (h.equals("role") || h.equals("rank") || h.contains("rolle")
                    || h.contains("rang") || h.contains("uloga"))) {
                roleIdx = i;
            }
        }
        if (nameIdx < 0) {
            // Fallback: assume column 0 is player, 1 is population. Works for the
            // bare-bones T4.6 roster table that often ships without a thead at all.
            if (headerCells.size() >= 2) {
                return new ColumnLayout(0, 1, -1);
            }
            return null;
        }
        return new ColumnLayout(nameIdx, popIdx, roleIdx);
    }

    /**
     * Read a numeric uid out of an {@code <a>} link, supporting both the
     * legacy query-string style ({@code ?uid=123}) and the newer path style
     * ({@code /profile/123}). Returns null when no link is present or no uid
     * matches.
     */
    static Long extractUidFromLink(Element cell) {
        for (Element link : cell.select("a[href]")) {
            String href = link.attr("href");
            Matcher m = UID_LINK_PATTERN.matcher(href);
            if (m.find()) {
                try { return Long.parseLong(m.group(1)); }
                catch (NumberFormatException ignored) { /* keep looking */ }
            }
        }
        return null;
    }

    // ---------- Bonuses ------------------------------------------------------

    /**
     * Pull every bonus name → level pair we can find. Walks a handful of
     * likely DOM shapes (a {@code <table>} of two-column rows, a {@code <dl>},
     * or {@code <li>} items inside a {@code .bonuses} list).
     */
    private static Map<String, Integer> parseBonuses(Document doc) {
        Map<String, Integer> out = new LinkedHashMap<>();

        // Bonuses table — scoped to alliance-bonus containers first, then a broader scan.
        Elements scopes = doc.select(".bonuses, .allianceBonus, #allianceBonus, #bonuses, .alliance-bonuses");
        if (scopes.isEmpty()) scopes = new Elements(doc.body() == null ? doc : doc.body());

        for (Element scope : scopes) {
            // <table> rows
            for (Element tr : scope.select("table tr")) {
                Elements cells = tr.select("td, th");
                if (cells.size() < 2) continue;
                String label = cells.get(0).text();
                String value = cells.get(cells.size() - 1).text();
                addBonusIfRecognised(out, label, value);
            }
            // <dl> pairs
            Elements dts = scope.select("dt");
            Elements dds = scope.select("dd");
            int pairs = Math.min(dts.size(), dds.size());
            for (int i = 0; i < pairs; i++) {
                addBonusIfRecognised(out, dts.get(i).text(), dds.get(i).text());
            }
            // <li> items shaped like "Diplomacy: 3"
            for (Element li : scope.select("li")) {
                String text = li.text();
                int colon = text.indexOf(':');
                if (colon > 0) {
                    addBonusIfRecognised(out, text.substring(0, colon), text.substring(colon + 1));
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static void addBonusIfRecognised(Map<String, Integer> out, String label, String value) {
        if (label == null) return;
        String norm = label.trim().toLowerCase(Locale.ROOT).replaceAll("[:\\s]+$", "");
        if (norm.isEmpty()) return;
        // Take just the head of the label up to the first colon/parenthesis.
        int cut = norm.indexOf('(');
        if (cut > 0) norm = norm.substring(0, cut).trim();
        cut = norm.indexOf(':');
        if (cut > 0) norm = norm.substring(0, cut).trim();

        String canonical = BONUS_LABELS.get(norm);
        Integer level = parseFirstInt(value);
        if (level == null) return;

        // Always store under the raw label (lowercased) so the raw JSON keeps
        // localized labels intact, AND under the canonical key when we know it.
        out.put(norm, level);
        if (canonical != null && !canonical.equals(norm)) {
            out.put(canonical, level);
        }
    }

    private static Integer parseFirstInt(String text) {
        if (text == null) return null;
        Matcher m = LEVEL_PATTERN.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    // ---------- JSON serialization (manual, tiny) ----------------------------

    /**
     * Build a JSON object string from the bonus map. Manual because the map
     * is small (≤10 entries) and avoiding an ObjectMapper dependency keeps
     * this parser dependency-free for unit testing.
     */
    static String toJson(Map<String, Integer> bonuses) {
        if (bonuses == null || bonuses.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(bonuses.size() * 16);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> e : bonuses.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\":");
            sb.append(e.getValue() == null ? "null" : e.getValue().toString());
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    // ---------- Value records ------------------------------------------------

    public record ParsedAllianceDetail(
            String description,
            List<ParsedAllianceMember> members,
            Integer bonusDiplomacy,
            Integer bonusMining,
            Integer bonusScout,
            Integer bonusBrewery,
            String rawBonusesJson
    ) {}

    public record ParsedAllianceMember(
            Long uid,
            String name,
            Integer population,
            String role
    ) {}

    private record ColumnLayout(int nameIdx, int popIdx, int roleIdx) {}
}
