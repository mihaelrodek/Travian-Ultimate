package com.travianbot.bot.scraper;

import java.util.List;

/**
 * One farmlist as scraped from the rally-point "Farm lists" tab.
 * <p>
 * {@code travianListId} is the Travian-side id used to address the list (the
 * {@code lid} query param on send requests). Used by the upsert logic to
 * reconcile against existing local rows — much more stable than matching on
 * the user-visible name.
 */
public record ParsedFarmlist(
        Long travianListId,
        String name,
        List<ParsedFarmlistTarget> targets
) {
}
