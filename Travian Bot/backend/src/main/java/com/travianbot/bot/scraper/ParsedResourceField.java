package com.travianbot.bot.scraper;

import com.travianbot.village.domain.ResourceType;

/**
 * Raw parse of one tile from {@code dorf1.php}. Immutable; persistence is a separate step.
 */
public record ParsedResourceField(
        int slotId,
        int gid,
        ResourceType type,
        int level,
        boolean underConstruction
) {
}
