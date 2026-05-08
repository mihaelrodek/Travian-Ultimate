package com.travianbot.bot.scraper;

/**
 * A single entry from the right-hand "my villages" sidebar switcher. Carries identity only —
 * the group is whatever the user configured in Travian's own "Village groups" feature (the
 * Manage groups dialog on the village picker) and we mirror it read-only.
 * <p>
 * Any field except {@code travianVillageId} may be null when the page layout hides the data
 * (e.g. groups are optional; some skins omit coordinates for the currently active row).
 */
public record ParsedSidebarVillage(
        Long travianVillageId,
        String name,
        Integer coordinateX,
        Integer coordinateY,
        String groupName,
        boolean active
) {
}
