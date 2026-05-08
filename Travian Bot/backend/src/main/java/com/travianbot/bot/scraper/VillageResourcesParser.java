package com.travianbot.bot.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the current resource stockpile + storage capacity from a dorf1.php
 * response.
 *
 * <p>T4.6 embeds a small JS blob near the top of the page that looks like:
 * <pre>{@code
 * <script type='text/javascript'>
 *   var resources = {
 *     production: {"l1": 2188, "l2": 1313, "l3": 1750, "l4": -3740, "l5": 2586},
 *     storage:    {"l1": 11272,"l2": 9468,"l3": 17970,"l4": 11352},
 *     maxStorage: {"l1": 37900,"l2": 37900,"l3": 37900,"l4": 80000}
 *   };
 * </script>
 * }</pre>
 * Keys map as: {@code l1=wood, l2=clay, l3=iron, l4=crop, l5=consumers} (we ignore l5 here —
 * crop consumers belong to the hero/troops view). {@code maxStorage.l1..l3} are warehouse
 * capacity (same for wood/clay/iron because a single warehouse building serves all three),
 * {@code maxStorage.l4} is granary capacity (separate building).
 *
 * <p>Why regex instead of evaluating the JS: we don't want to ship a JS engine just to
 * read six numbers, and Jsoup + regex is what the rest of the scraper already uses. The
 * parser is deliberately tolerant of whitespace and trailing-comma variations between
 * server generations; if any key is missing we return {@link Optional#empty()} rather
 * than guessing — the NPC check must never fire on partial data.
 */
public final class VillageResourcesParser {

    private static final Logger log = LoggerFactory.getLogger(VillageResourcesParser.class);

    /**
     * Carve out {@code storage: {...}} and {@code maxStorage: {...}} block bodies.
     * Non-greedy match on the inside — JS objects in this blob don't nest, so the
     * first closing brace is ours.
     */
    private static final Pattern STORAGE_BLOCK = Pattern.compile(
            "\\bstorage\\s*:\\s*\\{([^}]*)\\}",
            Pattern.DOTALL
    );
    private static final Pattern MAX_STORAGE_BLOCK = Pattern.compile(
            "\\bmaxStorage\\s*:\\s*\\{([^}]*)\\}",
            Pattern.DOTALL
    );
    private static final Pattern PRODUCTION_BLOCK = Pattern.compile(
            "\\bproduction\\s*:\\s*\\{([^}]*)\\}",
            Pattern.DOTALL
    );

    /** Matches {@code "l1": 11272} or {@code l1: 11272} (quotes optional) inside a block. */
    private static final Pattern LN_KEY_VALUE = Pattern.compile(
            "[\"']?l(\\d)[\"']?\\s*:\\s*(-?\\d+)"
    );

    private VillageResourcesParser() {
    }

    /**
     * Parse dorf1 HTML and return the stockpile snapshot, or empty if the blob is
     * missing or malformed. Never throws — a scrape with an unusual page shape
     * should log and move on, not fail the whole pipeline.
     */
    public static Optional<Stockpile> parse(String html) {
        if (html == null || html.isEmpty()) {
            return Optional.empty();
        }

        long[] storage = extractBlock(html, STORAGE_BLOCK);
        long[] maxStorage = extractBlock(html, MAX_STORAGE_BLOCK);
        long[] production = extractBlock(html, PRODUCTION_BLOCK); // optional

        // We need l1..l4 (wood/clay/iron/crop) stock and at minimum l1 and l4 of
        // maxStorage (warehouse capacity and granary capacity). The other
        // warehouse entries should match l1 but we don't enforce that — a
        // malformed page with l1 only is still usable.
        if (storage == null || maxStorage == null) {
            log.debug("No storage/maxStorage JS blob in dorf1 HTML — skipping stockpile parse");
            return Optional.empty();
        }
        if (storage[1] < 0 || storage[2] < 0 || storage[3] < 0 || storage[4] < 0) {
            // Missing any of the four resource stocks → empty means "not parsed".
            // Negative production is legal, but storage never is.
            log.warn("Stockpile JS blob missing keys (storage): l1={}, l2={}, l3={}, l4={}",
                    storage[1], storage[2], storage[3], storage[4]);
            return Optional.empty();
        }
        if (maxStorage[1] <= 0 || maxStorage[4] <= 0) {
            log.warn("Stockpile JS blob missing keys (maxStorage): warehouse={}, granary={}",
                    maxStorage[1], maxStorage[4]);
            return Optional.empty();
        }

        // Production is optional — older Travian pages may not emit the
        // production block, or our regex may miss a generation we haven't
        // tested yet. Return null when we can't read it so callers can tell
        // "not known yet" apart from "actually zero per hour".
        Long cropProduction = (production != null && production[4] != -1L) ? production[4] : null;

        return Optional.of(new Stockpile(
                storage[1], storage[2], storage[3], storage[4],
                maxStorage[1], maxStorage[4],
                cropProduction
        ));
    }

    /**
     * Returns an array of length 6 keyed 0-unused, 1..5 = lN value. Missing keys
     * come back as {@code -1} sentinel so callers can tell "zero stock" apart
     * from "key wasn't in the blob".
     */
    private static long[] extractBlock(String html, Pattern blockPattern) {
        Matcher blockM = blockPattern.matcher(html);
        if (!blockM.find()) {
            return null;
        }
        String body = blockM.group(1);

        long[] out = new long[6];
        for (int i = 0; i < out.length; i++) {
            out[i] = -1L;
        }
        Matcher kvM = LN_KEY_VALUE.matcher(body);
        while (kvM.find()) {
            int idx;
            try {
                idx = Integer.parseInt(kvM.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (idx < 1 || idx >= out.length) {
                continue;
            }
            try {
                out[idx] = Long.parseLong(kvM.group(2));
            } catch (NumberFormatException ignored) {
                // leave as -1
            }
        }
        return out;
    }

    /**
     * Snapshot of the resource panel at the moment a dorf1 page was scraped.
     * {@code warehouseCapacity} is shared by wood/clay/iron;
     * {@code granaryCapacity} is the separate crop-only capacity.
     * {@code cropProduction} is the per-hour crop balance (positive = net
     * surplus, negative = village is starving) — nullable so callers can tell
     * "not parsed" apart from "exactly zero per hour", which legitimately
     * happens on a freshly-built fields-only village.
     */
    public record Stockpile(
            long wood,
            long clay,
            long iron,
            long crop,
            long warehouseCapacity,
            long granaryCapacity,
            Long cropProduction
    ) {
    }
}
