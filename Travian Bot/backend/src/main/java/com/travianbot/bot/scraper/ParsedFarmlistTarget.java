package com.travianbot.bot.scraper;

/**
 * A single raid-target row scraped from a farmlist.
 * <p>
 * {@code troopsJson} is already serialized as the JSON map the rest of the
 * system expects — e.g. {@code {"t1":50,"t4":2}} — so the scraper does the
 * per-tribe → canonical translation in one place.
 * <p>
 * {@code travianTargetId} is the list-item id Travian uses on its send form;
 * having it lets the scheduler issue sends via a direct POST later without
 * needing to re-parse the page.
 */
public record ParsedFarmlistTarget(
        Long travianTargetId,
        String name,
        int coordinateX,
        int coordinateY,
        String troopsJson,
        boolean enabled
) {
}
