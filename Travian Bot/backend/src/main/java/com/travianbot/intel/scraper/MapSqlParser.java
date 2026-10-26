package com.travianbot.intel.scraper;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Travian's daily {@code map.sql} dump.
 * <p>
 * The dump is a plain text body composed of lines like:
 * <pre>
 * INSERT INTO `x_world` VALUES (123456,-50,12,1,4711,'Capital','9988','Ridder','22','Vikings',342,'EU','1','0');
 * </pre>
 * Columns, in order:
 * <ol>
 *   <li>mapId — integer tile id, signed</li>
 *   <li>x, y — coordinates, signed (range ~±400)</li>
 *   <li>tribeId — 0=uninhabited, 1=Romans, 2=Teutons, 3=Gauls, 4=Nature, 5=Natars, 6=Egyptians, 7=Huns</li>
 *   <li>vid — village id</li>
 *   <li>villageName — single-quoted, may contain {@code \'}</li>
 *   <li>uid — player id (sometimes string-quoted, sometimes integer; we accept both)</li>
 *   <li>playerName — single-quoted</li>
 *   <li>aid — alliance id; {@code 0} or empty means no alliance</li>
 *   <li>allianceName — single-quoted, empty when no alliance</li>
 *   <li>population — integer</li>
 *   <li>region — quoted, optional (newer T4.6 servers)</li>
 *   <li>capital — '0'/'1', optional</li>
 *   <li>harbour — '0'/'1', optional</li>
 * </ol>
 * <p>
 * <strong>Algorithm.</strong> We scan the body character-by-character with a
 * tiny state machine:
 * <ul>
 *   <li>Skip everything outside an INSERT statement (comments, set-statements,
 *       blank lines).</li>
 *   <li>When we hit {@code INSERT INTO}, advance until {@code VALUES (}, then
 *       collect a single-row tuple, then advance until {@code ;}, then back to
 *       scanning for the next INSERT.</li>
 *   <li>Inside a tuple we split on commas at depth-0, respecting single-quoted
 *       strings and the {@code \'} escape — a SQL-tolerant CSV parser, not a
 *       full SQL parser.</li>
 * </ul>
 * Rows with {@code tribeId == 0} (uninhabited) or {@code tribeId == 4}
 * (nature / oasis) or {@code uid == 0} are dropped silently — the caller
 * never needs to see them. Rows with fewer than 11 or more than 14 fields
 * are also dropped (defensive — different Travian server versions emit
 * different trailing columns).
 */
@Component
public class MapSqlParser {

    /** Mirror the static method as an instance method so it's injectable. */
    public List<ParsedMapRow> parse(String body) {
        return parseStatic(body);
    }

    public static List<ParsedMapRow> parseStatic(String body) {
        if (body == null || body.isEmpty()) return List.of();

        List<ParsedMapRow> out = new ArrayList<>();
        int i = 0;
        int len = body.length();

        while (i < len) {
            // Skip whitespace + line/block comments + non-INSERT lines.
            i = skipNoise(body, i);
            if (i >= len) break;

            // Look for "INSERT INTO" (case-insensitive); anything else, advance to next newline.
            if (!matchesKeyword(body, i, "INSERT")) {
                i = advanceToNewline(body, i);
                continue;
            }

            // Find the opening paren of the VALUES tuple. If we can't, bail out of this line.
            int valuesIdx = indexOfKeyword(body, i, "VALUES");
            if (valuesIdx < 0) {
                i = advanceToNewline(body, i);
                continue;
            }
            int open = body.indexOf('(', valuesIdx);
            if (open < 0) {
                i = advanceToNewline(body, i);
                continue;
            }

            // Extract the tuple content (between the matching parens, single-quote aware).
            int close = findClosingParen(body, open);
            if (close < 0) {
                // Malformed — give up on this statement.
                i = advanceToNewline(body, i);
                continue;
            }
            String tuple = body.substring(open + 1, close);
            ParsedMapRow row = parseTuple(tuple);
            if (row != null) out.add(row);

            // Advance past the closing paren; statement-terminating ';' may
            // follow but we don't strictly require it (defensive).
            i = close + 1;
        }
        return out;
    }

    // ---------- Tuple parsing ------------------------------------------------

    /**
     * Split a tuple body on top-level commas (respecting single-quoted strings
     * with {@code \'} escapes), then map fields by position. Returns null on
     * any row we deliberately skip.
     */
    static ParsedMapRow parseTuple(String tuple) {
        List<String> fields = splitFields(tuple);
        // Modern T4.6 / Annual Special formats append extra trailing flags
        // (16+ columns). We only consume positions 0–13; anything beyond is
        // ignored. Lower bound stays strict so we never half-decode a row.
        if (fields.size() < 11 || fields.size() > 24) return null;

        try {
            int mapId = parseInt(fields.get(0));
            int x = parseInt(fields.get(1));
            int y = parseInt(fields.get(2));
            int tribeId = parseInt(fields.get(3));
            // Skip uninhabited (0) and nature (4) up front — every consumer wants real players.
            if (tribeId == 0 || tribeId == 4) return null;

            long vid = parseLong(fields.get(4));
            String villageName = unquote(fields.get(5));
            long uid = parseLong(fields.get(6));
            if (uid == 0L) return null;

            String playerName = unquote(fields.get(7));
            Long aid = parseLongNullable(fields.get(8));
            String allianceName = unquote(fields.get(9));
            // Travian uses '' for "no alliance" — normalise to null so downstream
            // doesn't have to remember the convention.
            if (allianceName != null && allianceName.isEmpty()) allianceName = null;
            if (aid != null && aid == 0L) aid = null;

            int population = parseInt(fields.get(10));

            String region = fields.size() > 11 ? unquote(fields.get(11)) : null;
            Boolean capital = fields.size() > 12 ? parseBoolFlag(fields.get(12)) : null;
            Boolean harbour = fields.size() > 13 ? parseBoolFlag(fields.get(13)) : null;

            return new ParsedMapRow(
                    mapId, x, y, tribeId, vid, villageName, uid, playerName,
                    aid, allianceName, population, region, capital, harbour);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Comma-split honouring single-quoted strings and the {@code \'} escape. */
    static List<String> splitFields(String tuple) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        int len = tuple.length();
        for (int i = 0; i < len; i++) {
            char c = tuple.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < len) {
                    // Preserve the escape sequence as-is so unquote() can decode it.
                    cur.append(c);
                    cur.append(tuple.charAt(i + 1));
                    i++;
                } else if (c == '\'') {
                    cur.append(c);
                    inString = false;
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '\'') {
                    cur.append(c);
                    inString = true;
                } else if (c == ',') {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    // ---------- Whitespace / comment helpers ---------------------------------

    private static int skipNoise(String s, int i) {
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < len && s.charAt(i + 1) == '-') {
                // Line comment
                i = advanceToNewline(s, i);
            } else if (c == '#') {
                // MySQL-style line comment (map.sql sometimes contains these).
                i = advanceToNewline(s, i);
            } else if (c == '/' && i + 1 < len && s.charAt(i + 1) == '*') {
                // Block comment
                int end = s.indexOf("*/", i + 2);
                i = (end < 0) ? len : end + 2;
            } else {
                return i;
            }
        }
        return i;
    }

    private static int advanceToNewline(String s, int i) {
        int nl = s.indexOf('\n', i);
        return nl < 0 ? s.length() : nl + 1;
    }

    private static boolean matchesKeyword(String s, int i, String kw) {
        if (i + kw.length() > s.length()) return false;
        for (int k = 0; k < kw.length(); k++) {
            if (Character.toUpperCase(s.charAt(i + k)) != kw.charAt(k)) return false;
        }
        return true;
    }

    private static int indexOfKeyword(String s, int from, String kw) {
        // Case-insensitive forward search bounded by ';' so we don't leak into the next statement.
        int len = s.length();
        int kwLen = kw.length();
        for (int i = from; i + kwLen <= len; i++) {
            char c = s.charAt(i);
            if (c == ';') return -1;
            if (matchesKeyword(s, i, kw)) return i;
        }
        return -1;
    }

    /**
     * Walk forward from the opening paren and return the index of the matching
     * close paren, honouring single-quoted strings. Returns -1 if not found
     * (malformed input).
     */
    private static int findClosingParen(String s, int openIdx) {
        int len = s.length();
        boolean inString = false;
        for (int i = openIdx + 1; i < len; i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < len) { i++; continue; }
                if (c == '\'') inString = false;
            } else {
                if (c == '\'') inString = true;
                else if (c == ')') return i;
            }
        }
        return -1;
    }

    // ---------- Field decoders -----------------------------------------------

    private static int parseInt(String raw) {
        return Integer.parseInt(stripOuterQuotes(raw).trim());
    }

    private static long parseLong(String raw) {
        return Long.parseLong(stripOuterQuotes(raw).trim());
    }

    private static Long parseLongNullable(String raw) {
        String v = stripOuterQuotes(raw).trim();
        if (v.isEmpty()) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Decode a single-quoted SQL string: drop the outer quotes and unescape
     * {@code \\} and {@code \'}. Numeric fields wrapped in quotes (Travian
     * sometimes string-quotes uids and aids) come through cleanly because we
     * also strip the quotes for the numeric parsers.
     */
    static String unquote(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return v;
        // SQL NULL literal — bare, unquoted. Modern T4.6 dumps use NULL for
        // empty region / alliance / etc instead of the empty string.
        if ("NULL".equalsIgnoreCase(v)) return null;
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            v = v.substring(1, v.length() - 1);
        }
        StringBuilder out = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char next = v.charAt(i + 1);
                // Translate common escapes; leave anything else as the literal next char.
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    case '\'' -> out.append('\'');
                    case '"' -> out.append('"');
                    default -> out.append(next);
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripOuterQuotes(String raw) {
        String v = raw.trim();
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static Boolean parseBoolFlag(String raw) {
        String v = stripOuterQuotes(raw).trim();
        if (v.isEmpty() || "NULL".equalsIgnoreCase(v)) return null;
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * One row from {@code map.sql} corresponding to a single inhabited tile.
     * Optional trailing fields ({@code region}, {@code capital}, {@code harbour})
     * are null when the server's dump doesn't include them.
     */
    public record ParsedMapRow(
            int mapId,
            int x,
            int y,
            int tribeId,
            long vid,
            String villageName,
            long uid,
            String playerName,
            Long aid,
            String allianceName,
            int population,
            String region,
            Boolean capital,
            Boolean harbour
    ) {}
}
