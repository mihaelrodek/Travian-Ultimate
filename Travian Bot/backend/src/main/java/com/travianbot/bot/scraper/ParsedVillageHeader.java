package com.travianbot.bot.scraper;

/**
 * The identity fields of the currently active village, extracted from a dorf1/dorf2 page.
 * Any field may be null if the page didn't provide it.
 */
public record ParsedVillageHeader(
        Long travianVillageId,
        String name,
        Integer coordinateX,
        Integer coordinateY
) {
}
