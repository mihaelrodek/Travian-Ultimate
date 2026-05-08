package com.travianbot.troops.domain;

import com.travianbot.account.domain.Tribe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4.6 Legends troop catalogue, keyed by {@link Tribe}. Kept in code rather
 * than in the DB for the same reason as {@code BuildingCatalog} — the list is
 * static game data and we'd rather evolve it without migrations.
 * <p>
 * Troop codes use the Travian {@code t1}..{@code t10} convention tribe-qualified
 * (e.g. {@code ROMANS_t1}) so there's no risk of two tribes' codes colliding
 * when the DB stores them as plain strings. The {@link TroopInfo#troopSlot()}
 * field preserves the underlying {@code t1..t10} index — the browser client
 * needs it to address the right input box on {@code /build.php?t=21&gid=19}.
 * <p>
 * Slots 9/10 (chief + settler) are omitted — they train in the Residence/Palace,
 * not the three mainline military buildings that this queue handles.
 */
public final class TroopCatalog {

    /** One row in the catalogue. */
    public record TroopInfo(
            String code,
            String displayName,
            TroopBuilding building,
            int troopSlot
    ) {}

    private static final Map<Tribe, Map<String, TroopInfo>> BY_TRIBE;

    static {
        Map<Tribe, Map<String, TroopInfo>> m = new EnumMap<>(Tribe.class);
        m.put(Tribe.ROMANS, unit(Tribe.ROMANS, List.of(
                new Def("Legionnaire",          TroopBuilding.BARRACKS, 1),
                new Def("Praetorian",           TroopBuilding.BARRACKS, 2),
                new Def("Imperian",              TroopBuilding.BARRACKS, 3),
                new Def("Equites Legati",       TroopBuilding.STABLE,   4),
                new Def("Equites Imperatoris",  TroopBuilding.STABLE,   5),
                new Def("Equites Caesaris",     TroopBuilding.STABLE,   6),
                new Def("Battering Ram",        TroopBuilding.WORKSHOP, 7),
                new Def("Fire Catapult",        TroopBuilding.WORKSHOP, 8)
        )));
        m.put(Tribe.TEUTONS, unit(Tribe.TEUTONS, List.of(
                new Def("Clubswinger",          TroopBuilding.BARRACKS, 1),
                new Def("Spearman",             TroopBuilding.BARRACKS, 2),
                new Def("Axeman",               TroopBuilding.BARRACKS, 3),
                new Def("Scout",                TroopBuilding.STABLE,   4),
                new Def("Paladin",              TroopBuilding.STABLE,   5),
                new Def("Teutonic Knight",      TroopBuilding.STABLE,   6),
                new Def("Ram",                  TroopBuilding.WORKSHOP, 7),
                new Def("Catapult",             TroopBuilding.WORKSHOP, 8)
        )));
        m.put(Tribe.GAULS, unit(Tribe.GAULS, List.of(
                new Def("Phalanx",              TroopBuilding.BARRACKS, 1),
                new Def("Swordsman",            TroopBuilding.BARRACKS, 2),
                new Def("Pathfinder",           TroopBuilding.STABLE,   3),
                new Def("Theutates Thunder",    TroopBuilding.STABLE,   4),
                new Def("Druidrider",           TroopBuilding.STABLE,   5),
                new Def("Haeduan",              TroopBuilding.STABLE,   6),
                new Def("Ram",                  TroopBuilding.WORKSHOP, 7),
                new Def("Trebuchet",            TroopBuilding.WORKSHOP, 8)
        )));
        m.put(Tribe.EGYPTIANS, unit(Tribe.EGYPTIANS, List.of(
                new Def("Slave Militia",        TroopBuilding.BARRACKS, 1),
                new Def("Ash Warden",           TroopBuilding.BARRACKS, 2),
                new Def("Khopesh Warrior",      TroopBuilding.BARRACKS, 3),
                new Def("Sopdu Explorer",       TroopBuilding.STABLE,   4),
                new Def("Anhur Guard",          TroopBuilding.STABLE,   5),
                new Def("Resheph Chariot",      TroopBuilding.STABLE,   6),
                new Def("Ram",                  TroopBuilding.WORKSHOP, 7),
                new Def("Stone Catapult",       TroopBuilding.WORKSHOP, 8)
        )));
        m.put(Tribe.HUNS, unit(Tribe.HUNS, List.of(
                new Def("Mercenary",            TroopBuilding.BARRACKS, 1),
                new Def("Bowman",               TroopBuilding.BARRACKS, 2),
                new Def("Spotter",              TroopBuilding.STABLE,   3),
                new Def("Steppe Rider",         TroopBuilding.STABLE,   4),
                new Def("Marksman",             TroopBuilding.STABLE,   5),
                new Def("Marauder",             TroopBuilding.STABLE,   6),
                new Def("Ram",                  TroopBuilding.WORKSHOP, 7),
                new Def("Catapult",             TroopBuilding.WORKSHOP, 8)
        )));
        m.put(Tribe.SPARTANS, unit(Tribe.SPARTANS, List.of(
                new Def("Hoplite",              TroopBuilding.BARRACKS, 1),
                new Def("Sentinel",             TroopBuilding.BARRACKS, 2),
                new Def("Shieldsman",           TroopBuilding.BARRACKS, 3),
                new Def("Twin-Horn Warrior",    TroopBuilding.STABLE,   4),
                new Def("Horseman",             TroopBuilding.STABLE,   5),
                new Def("Chariot Fighter",      TroopBuilding.STABLE,   6),
                new Def("Ram",                  TroopBuilding.WORKSHOP, 7),
                new Def("Ballista",             TroopBuilding.WORKSHOP, 8)
        )));
        // Vikings are defined on the Tribe enum but aren't a canonical T4.6
        // Legends tribe at the time of writing. We still publish a placeholder
        // set so the catalog API never hands the frontend an empty list for a
        // valid tribe — label them as {@code Viking Slot N} so it's clear these
        // are stubs until real data is available.
        m.put(Tribe.VIKINGS, unit(Tribe.VIKINGS, List.of(
                new Def("Viking Slot 1",        TroopBuilding.BARRACKS, 1),
                new Def("Viking Slot 2",        TroopBuilding.BARRACKS, 2),
                new Def("Viking Slot 3",        TroopBuilding.BARRACKS, 3),
                new Def("Viking Slot 4",        TroopBuilding.STABLE,   4),
                new Def("Viking Slot 5",        TroopBuilding.STABLE,   5),
                new Def("Viking Slot 6",        TroopBuilding.STABLE,   6),
                new Def("Viking Slot 7",        TroopBuilding.WORKSHOP, 7),
                new Def("Viking Slot 8",        TroopBuilding.WORKSHOP, 8)
        )));
        BY_TRIBE = Collections.unmodifiableMap(m);
    }

    private TroopCatalog() {}

    /** Entries for one tribe, in display order (slot 1 → 8). */
    public static List<TroopInfo> forTribe(Tribe tribe) {
        if (tribe == null) return List.of();
        Map<String, TroopInfo> map = BY_TRIBE.get(tribe);
        return map == null ? List.of() : List.copyOf(map.values());
    }

    /** Look up one troop by its code within a tribe — {@code null} if unknown. */
    public static TroopInfo lookup(Tribe tribe, String code) {
        if (tribe == null || code == null) return null;
        Map<String, TroopInfo> map = BY_TRIBE.get(tribe);
        return map == null ? null : map.get(code);
    }

    /**
     * Builds a tribe-qualified code like {@code ROMANS_t1}. Exposed so callers
     * that scrape a troop identity (not typical yet, but handy for tests) can
     * round-trip through the same naming scheme the catalog uses.
     */
    public static String codeFor(Tribe tribe, int troopSlot) {
        return tribe.name() + "_t" + troopSlot;
    }

    // ---- internals --------------------------------------------------------

    private record Def(String displayName, TroopBuilding building, int slot) {}

    private static Map<String, TroopInfo> unit(Tribe tribe, List<Def> defs) {
        Map<String, TroopInfo> map = new LinkedHashMap<>();
        for (Def d : defs) {
            String code = codeFor(tribe, d.slot);
            map.put(code, new TroopInfo(code, d.displayName, d.building, d.slot));
        }
        return Collections.unmodifiableMap(map);
    }
}
