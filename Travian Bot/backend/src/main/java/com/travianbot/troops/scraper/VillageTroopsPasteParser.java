package com.travianbot.troops.scraper;

import com.travianbot.incoming.analyzer.TroopSpeedCatalog;
import com.travianbot.incoming.scraper.RallyPointIncomingParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code Village overview > Troops > Troops in villages} screen
 * into a list of {@link ParsedVillageRow} records. The table is tab-separated
 * after Ctrl+A → Ctrl+C from the rendered page:
 * <pre>
 *   Village	Phalanx	Swordsman	Pathfinder	...	Hero
 *   S00 Splinter	0	0	0	...	0
 *   S01 Michelangelo	3	76240	23	...	1
 *   ...
 *   Sum	42284	137399	1933	...	1
 * </pre>
 * <p>
 * Strategy:
 * <ol>
 *   <li>Strip bidi marks (browser injects them around large numbers, same
 *       breakage as the rally-point paste).</li>
 *   <li>Locate the header row by looking for the literal {@code "Village"}
 *       at the start of a line followed by at least 10 tab-separated tokens.
 *       Capture the troop-name tokens between the leading "Village" and the
 *       trailing "Hero" so we can infer the tribe.</li>
 *   <li>Each subsequent line that starts with a non-numeric token and has
 *       11 numeric columns is a village row. Parse until we hit "Sum" /
 *       blank line / a line that doesn't fit the shape.</li>
 *   <li>Tribe id is inferred by matching the captured troop names against
 *       {@link TroopSpeedCatalog}. Unknown → null (counts still stored).</li>
 * </ol>
 * <p>
 * The parser is loud-tolerant: anything that doesn't fit the shape is
 * skipped silently rather than throwing. The caller decides what to do
 * with an empty result.
 */
public final class VillageTroopsPasteParser {

    /**
     * Matches the header row. Capture 1 = the tab-joined troop-name block
     * (between "Village" and "Hero"). We anchor on line start + the literal
     * "Village" + at least 5 tab-separated tokens before "Hero" so a stray
     * paragraph won't masquerade as the table header.
     */
    private static final Pattern HEADER_LINE = Pattern.compile(
            "(?m)^Village\\t([^\\n\\r]+?)\\tHero\\s*$"
    );

    /** Sentinel for the "Sum" footer row Travian renders below the data. */
    private static final Pattern SUM_LINE = Pattern.compile(
            "(?im)^Sum\\b"
    );

    private VillageTroopsPasteParser() {
    }

    public static ParsedTroopsTable parse(String body) {
        if (body == null || body.isBlank()) return ParsedTroopsTable.empty();
        String cleaned = RallyPointIncomingParser.stripBidiMarks(body);

        Matcher header = HEADER_LINE.matcher(cleaned);
        if (!header.find()) return ParsedTroopsTable.empty();
        String middle = header.group(1);
        // 9 tabs in the middle block → 10 troop slots after counting "Hero".
        // Actually the in-game table has exactly 10 troop columns + 1 hero,
        // so the captured middle block must have exactly 9 tabs between its
        // 10 troop names. We don't strictly enforce that — if the format
        // shifts we'd rather log a warning than silently misalign columns.
        String[] troopNames = middle.split("\\t", -1);

        Integer tribeId = inferTribeId(troopNames);

        // Lines below the header — start scan from the end of the header
        // line; stop at the Sum footer (if present) or end-of-text.
        int dataStart = header.end();
        int dataEnd = cleaned.length();
        Matcher sum = SUM_LINE.matcher(cleaned);
        if (sum.find(dataStart)) {
            dataEnd = sum.start();
        }

        List<ParsedVillageRow> rows = new ArrayList<>();
        String dataBlock = cleaned.substring(dataStart, dataEnd);
        for (String rawLine : dataBlock.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            ParsedVillageRow row = parseDataLine(line);
            if (row != null) rows.add(row);
        }

        return new ParsedTroopsTable(tribeId, troopNames, rows);
    }

    /**
     * One data row: village name + 10 troop counts + 1 hero count.
     * Returns null when the line doesn't have the expected 11-column shape.
     */
    private static ParsedVillageRow parseDataLine(String line) {
        String[] parts = line.split("\\t", -1);
        if (parts.length < 12) return null; // village + 11 numbers
        String villageName = parts[0].trim();
        if (villageName.isEmpty()) return null;
        // Last 11 tokens must be integers.
        int[] troops = new int[10];
        int hero;
        try {
            for (int i = 0; i < 10; i++) {
                troops[i] = Integer.parseInt(parts[1 + i].trim().replaceAll("[^\\d-]", ""));
            }
            hero = Integer.parseInt(parts[11].trim().replaceAll("[^\\d-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        return new ParsedVillageRow(villageName, troops, hero);
    }

    /**
     * Match the captured troop-name tokens against every tribe in the
     * catalog and return the tribe whose unit names align best. We require
     * a strict match of at least 6 names (matches the tribe's first-tier
     * units which never change). Returns null when nothing matches well.
     */
    private static Integer inferTribeId(String[] names) {
        if (names == null || names.length < 6) return null;
        Integer best = null;
        int bestHits = 0;
        for (int tribeId = 1; tribeId <= 8; tribeId++) {
            int hits = countMatches(names, tribeId);
            if (hits > bestHits) {
                bestHits = hits;
                best = tribeId;
            }
        }
        return bestHits >= 6 ? best : null;
    }

    private static int countMatches(String[] headerNames, int tribeId) {
        int hits = 0;
        for (String n : headerNames) {
            String trimmed = n.trim();
            if (trimmed.isEmpty()) continue;
            for (int t = 1; t <= 10; t++) {
                TroopSpeedCatalog.TroopSpec spec = TroopSpeedCatalog.get(tribeId, t);
                if (spec == null) continue;
                if (trimmed.equalsIgnoreCase(spec.name())) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    /** One parsed village row before village-id resolution. */
    public record ParsedVillageRow(String villageName, int[] troops, int hero) {
    }

    /**
     * Result envelope. {@code tribeId} may be null when the header didn't
     * match a known catalogue tribe. {@code troopNames} preserves the
     * original header order so the UI can label columns even when tribe
     * inference failed.
     */
    public record ParsedTroopsTable(Integer tribeId, String[] troopNames,
                                    List<ParsedVillageRow> rows) {
        public static ParsedTroopsTable empty() {
            return new ParsedTroopsTable(null, new String[0], List.of());
        }
    }
}
