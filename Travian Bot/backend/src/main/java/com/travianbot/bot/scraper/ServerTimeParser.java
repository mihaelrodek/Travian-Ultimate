package com.travianbot.bot.scraper;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the game server's UTC offset from a Travian page.
 * <p>
 * T4.6 renders the current server time in the page footer / header strip with
 * a format roughly like:
 * <pre>
 *   Server time 09:05:12 (UTC +01:00)
 * </pre>
 * The scraper cares about the offset, not the wall-clock time — the local
 * machine already tells us what "now" is, and the user's host clock is the
 * ground truth. The offset lets us translate scraped countdown finish times
 * between the server's zone and the user's zone when rendering.
 * <p>
 * We return offsets in whole minutes so UTC+05:30 (India) and UTC+05:45 (Nepal)
 * round-trip losslessly through the DB integer column.
 */
public final class ServerTimeParser {

    private static final Logger log = LoggerFactory.getLogger(ServerTimeParser.class);

    /**
     * Matches "UTC +01:00", "(UTC-5:00)", "GMT +02:30", "UTC+1", etc.
     * Sign defaults to +. Minutes default to 00.
     */
    private static final Pattern OFFSET_PATTERN = Pattern.compile(
            "(?:UTC|GMT)\\s*([+-])\\s*(\\d{1,2})(?::(\\d{2}))?",
            Pattern.CASE_INSENSITIVE
    );

    private ServerTimeParser() {
    }

    /**
     * Finds the server UTC offset on the page.
     *
     * @return offset in minutes from UTC (e.g. 60 for UTC+01:00), or {@code null}
     *         if no offset marker was found.
     */
    public static Integer parseOffsetMinutes(Document doc) {
        if (doc == null) return null;

        // Try the common anchors first: server time label + footer strip.
        // Fall back to the raw page text if the selectors don't match — the
        // offset token is distinctive enough that a whole-page search is safe.
        String[] candidates = readCandidates(doc);
        for (String text : candidates) {
            Integer offset = extractOffsetMinutes(text);
            if (offset != null) return offset;
        }
        return null;
    }

    /**
     * Overload that parses raw HTML. Prefer the {@link Document} variant when
     * the caller has already parsed the page — Jsoup parses are not free.
     */
    public static Integer parseOffsetMinutes(String html) {
        if (html == null || html.isBlank()) return null;
        return parseOffsetMinutes(org.jsoup.Jsoup.parse(html));
    }

    /**
     * Visible for testing. Extracts the offset token from a text snippet.
     */
    static Integer extractOffsetMinutes(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = OFFSET_PATTERN.matcher(text);
        if (!m.find()) return null;
        try {
            String sign = m.group(1);
            int hours = Integer.parseInt(m.group(2));
            int minutes = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
            int total = hours * 60 + minutes;
            if ("-".equals(sign)) total = -total;
            // Sanity: real-world offsets are within ±14:00 (Kiribati to Samoa).
            if (total < -14 * 60 || total > 14 * 60) {
                log.debug("ServerTimeParser rejecting implausible offset: {}", total);
                return null;
            }
            return total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String[] readCandidates(Document doc) {
        // Travian skins have drifted across a few selectors over the years; walk
        // the narrow ones first so we don't regex-scan the whole page unless we
        // have to.
        String[] selectors = {
                "#servertime",
                ".servertime",
                "#footer",
                ".footer",
                "#stc",
                ".stc"
        };
        String[] out = new String[selectors.length + 1];
        for (int i = 0; i < selectors.length; i++) {
            Element el = doc.selectFirst(selectors[i]);
            out[i] = el != null ? el.text() : null;
        }
        // Final fallback — the full body text.
        out[selectors.length] = doc.body() != null ? doc.body().text() : null;
        return out;
    }
}
