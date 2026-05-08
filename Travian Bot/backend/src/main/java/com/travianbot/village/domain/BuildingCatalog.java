package com.travianbot.village.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T4.6 Legends building gid catalog.
 * <p>
 * Canonical mapping from the in-game {@code gid} to the building's display name. gid 0 is
 * reserved for "empty slot". Resource fields (gids 1..4) are intentionally excluded here —
 * they're modelled as {@link ResourceField}s, not {@link Building}s.
 * <p>
 * Maintained in code so we can add buildings without DB migrations. The list below is taken
 * from the public T4.6 game data and matches TravBotSharp's {@code BuildingEnum}.
 */
public final class BuildingCatalog {

    private static final Map<Integer, String> NAMES;

    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(0, "Empty");
        // 1..4 reserved for resource fields (tracked as ResourceField).
        m.put(5, "Sawmill");
        m.put(6, "Brickyard");
        m.put(7, "Iron Foundry");
        m.put(8, "Grain Mill");
        m.put(9, "Bakery");
        m.put(10, "Warehouse");
        m.put(11, "Granary");
        m.put(12, "Blacksmith");
        m.put(13, "Armoury");
        m.put(14, "Tournament Square");
        m.put(15, "Main Building");
        m.put(16, "Rally Point");
        m.put(17, "Marketplace");
        m.put(18, "Embassy");
        m.put(19, "Barracks");
        m.put(20, "Stable");
        m.put(21, "Workshop");
        m.put(22, "Academy");
        m.put(23, "Cranny");
        m.put(24, "Town Hall");
        m.put(25, "Residence");
        m.put(26, "Palace");
        m.put(27, "Treasury");
        m.put(28, "Trade Office");
        m.put(29, "Great Barracks");
        m.put(30, "Great Stable");
        m.put(31, "City Wall");
        m.put(32, "Earth Wall");
        m.put(33, "Palisade");
        m.put(34, "Stonemason's Lodge");
        m.put(35, "Brewery");
        m.put(36, "Trapper");
        m.put(37, "Hero's Mansion");
        m.put(38, "Great Warehouse");
        m.put(39, "Great Granary");
        m.put(40, "Wonder of the World");
        m.put(41, "Horse Drinking Trough");
        m.put(42, "Stone Wall");
        m.put(43, "Makeshift Wall");
        m.put(44, "Command Center");
        m.put(45, "Waterworks");
        NAMES = Collections.unmodifiableMap(m);
    }

    private BuildingCatalog() {
    }

    /** Returns the canonical display name for a gid, or {@code "Unknown (gid=X)"} if not listed. */
    public static String nameFor(int gid) {
        String name = NAMES.get(gid);
        return name != null ? name : "Unknown (gid=" + gid + ")";
    }

    /** Live view of the full catalog. Useful for /api/buildings/catalog. */
    public static Map<Integer, String> all() {
        return NAMES;
    }
}
