package com.travianbot.bot.scraper;

/**
 * Raw parse of one building slot from {@code dorf2.php}.
 */
public record ParsedBuilding(
        int slotId,
        int gid,
        String name,
        int level,
        boolean underConstruction
) {
}
