package com.travianbot.incoming.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalogue of T4.6 Legends troop base speeds (fields-per-hour at
 * server-speed x1, no modifiers). Keyed by {@code (tribeId, tIndex)} so a
 * lookup matches the same numbering scheme used by the frontend
 * {@code travianTroops.ts} catalogue and the rally-point {@code troop[tN]}
 * form fields.
 * <p>
 * Roles mirror the frontend tags exactly ({@code inf_def}, {@code inf_off},
 * {@code cav_off}, {@code cav_def}, {@code scout}, {@code ram}, {@code cat},
 * {@code chief}, {@code settler}) so the analyzer can filter by role using
 * the same vocabulary the UI uses.
 * <p>
 * Tribe ids align with map.sql conventions:
 * <ul>
 *   <li>1 — Romans</li>
 *   <li>2 — Teutons</li>
 *   <li>3 — Gauls</li>
 *   <li>5 — Natars (skipped — Natars do not attack player villages in normal play)</li>
 *   <li>6 — Egyptians</li>
 *   <li>7 — Huns</li>
 *   <li>8 — Spartans</li>
 * </ul>
 * The Natars catalogue intentionally absent — Natar attacks come only from
 * WW villages on a fixed schedule with a known unit, and the user already
 * knows to expect those. Adding them here would clutter the candidate list.
 * <p>
 * The numbers are the well-documented T4.6 Legends base speeds. They are
 * canonical for analytics — Kirilloid's calculator uses the same table.
 */
public final class TroopSpeedCatalog {

    /**
     * One catalogue entry. {@code role} is a stable string tag rather than an
     * enum so the value can flow through to the wire DTO without translation.
     */
    public record TroopSpec(int tribeId, int tIndex, String name, int baseFieldsPerHour, String role) {
    }

    /** Roles considered "attackable" — what the solver iterates over. */
    public static final Set<String> ATTACKABLE_ROLES = Set.of(
            "inf_def", "inf_off", "cav_off", "cav_def", "scout", "ram", "cat"
    );

    private static final Map<Long, TroopSpec> BY_KEY;
    private static final List<TroopSpec> ALL;
    private static final List<TroopSpec> ATTACKABLE;

    static {
        Map<Long, TroopSpec> map = new LinkedHashMap<>();
        List<TroopSpec> all = new ArrayList<>();

        // ---- Romans (1) ----
        addTribe(map, all, 1, new TroopSpec[]{
                new TroopSpec(1, 1, "Legionnaire",         6,  "inf_def"),
                new TroopSpec(1, 2, "Praetorian",          5,  "inf_def"),
                new TroopSpec(1, 3, "Imperian",            7,  "inf_off"),
                new TroopSpec(1, 4, "Equites Legati",      16, "scout"),
                new TroopSpec(1, 5, "Equites Imperatoris", 14, "cav_off"),
                new TroopSpec(1, 6, "Equites Caesaris",    10, "cav_off"),
                new TroopSpec(1, 7, "Battering Ram",       4,  "ram"),
                new TroopSpec(1, 8, "Fire Catapult",       3,  "cat"),
                new TroopSpec(1, 9, "Senator",             4,  "chief"),
                new TroopSpec(1, 10, "Settler",            5,  "settler"),
        });

        // ---- Teutons (2) ----
        addTribe(map, all, 2, new TroopSpec[]{
                new TroopSpec(2, 1, "Clubswinger",     7,  "inf_off"),
                new TroopSpec(2, 2, "Spearman",        7,  "inf_def"),
                new TroopSpec(2, 3, "Axeman",          6,  "inf_off"),
                new TroopSpec(2, 4, "Scout",           9,  "scout"),
                new TroopSpec(2, 5, "Paladin",         10, "cav_def"),
                new TroopSpec(2, 6, "Teutonic Knight", 9,  "cav_off"),
                new TroopSpec(2, 7, "Ram",             4,  "ram"),
                new TroopSpec(2, 8, "Catapult",        3,  "cat"),
                new TroopSpec(2, 9, "Chief",           4,  "chief"),
                new TroopSpec(2, 10, "Settler",        5,  "settler"),
        });

        // ---- Gauls (3) ----
        addTribe(map, all, 3, new TroopSpec[]{
                new TroopSpec(3, 1, "Phalanx",            7,  "inf_def"),
                new TroopSpec(3, 2, "Swordsman",          6,  "inf_off"),
                new TroopSpec(3, 3, "Pathfinder",         17, "scout"),
                new TroopSpec(3, 4, "Theutates Thunder",  19, "cav_off"),
                new TroopSpec(3, 5, "Druidrider",         16, "cav_def"),
                new TroopSpec(3, 6, "Haeduan",            13, "cav_off"),
                new TroopSpec(3, 7, "Ram",                4,  "ram"),
                new TroopSpec(3, 8, "Trebuchet",          3,  "cat"),
                new TroopSpec(3, 9, "Chieftain",          5,  "chief"),
                new TroopSpec(3, 10, "Settler",           5,  "settler"),
        });

        // ---- Egyptians (6) ----
        addTribe(map, all, 6, new TroopSpec[]{
                new TroopSpec(6, 1, "Slave Militia",   7,  "inf_def"),
                new TroopSpec(6, 2, "Ash Warden",      6,  "inf_def"),
                new TroopSpec(6, 3, "Khopesh Warrior", 7,  "inf_off"),
                new TroopSpec(6, 4, "Sopdu Explorer",  16, "scout"),
                new TroopSpec(6, 5, "Anhur Guard",     10, "cav_def"),
                new TroopSpec(6, 6, "Resheph Chariot", 10, "cav_off"),
                new TroopSpec(6, 7, "Ram",             4,  "ram"),
                new TroopSpec(6, 8, "Stone Catapult",  3,  "cat"),
                new TroopSpec(6, 9, "Nomarch",         4,  "chief"),
                new TroopSpec(6, 10, "Settler",        5,  "settler"),
        });

        // ---- Huns (7) ----
        addTribe(map, all, 7, new TroopSpec[]{
                new TroopSpec(7, 1, "Mercenary",    6,  "inf_off"),
                new TroopSpec(7, 2, "Bowman",       6,  "inf_off"),
                new TroopSpec(7, 3, "Spotter",      19, "scout"),
                new TroopSpec(7, 4, "Steppe Rider", 16, "cav_off"),
                new TroopSpec(7, 5, "Marksman",     14, "cav_off"),
                new TroopSpec(7, 6, "Marauder",     11, "cav_off"),
                new TroopSpec(7, 7, "Ram",          4,  "ram"),
                new TroopSpec(7, 8, "Catapult",     3,  "cat"),
                new TroopSpec(7, 9, "Logades",      4,  "chief"),
                new TroopSpec(7, 10, "Settler",     5,  "settler"),
        });

        // ---- Spartans (8) ----
        addTribe(map, all, 8, new TroopSpec[]{
                new TroopSpec(8, 1, "Hoplite",             7,  "inf_def"),
                new TroopSpec(8, 2, "Sentinel",            9,  "inf_def"),
                new TroopSpec(8, 3, "Shieldsman",          7,  "inf_off"),
                new TroopSpec(8, 4, "Twinhorn Warrior",    16, "scout"),
                new TroopSpec(8, 5, "Elpida Rider",        13, "cav_def"),
                new TroopSpec(8, 6, "Corinthian Crusher",  10, "cav_off"),
                new TroopSpec(8, 7, "Ram",                 4,  "ram"),
                new TroopSpec(8, 8, "Ballista",            3,  "cat"),
                new TroopSpec(8, 9, "Ephor",               4,  "chief"),
                new TroopSpec(8, 10, "Settler",            5,  "settler"),
        });

        BY_KEY = Collections.unmodifiableMap(map);
        ALL = Collections.unmodifiableList(all);
        List<TroopSpec> attackable = new ArrayList<>();
        for (TroopSpec t : all) {
            if (ATTACKABLE_ROLES.contains(t.role())) attackable.add(t);
        }
        ATTACKABLE = Collections.unmodifiableList(attackable);
    }

    private static void addTribe(Map<Long, TroopSpec> map, List<TroopSpec> all,
                                 int tribeId, TroopSpec[] troops) {
        for (TroopSpec t : troops) {
            map.put(key(t.tribeId(), t.tIndex()), t);
            all.add(t);
        }
        // tribeId arg is for clarity at the call site; the actual id is on the spec.
        if (troops.length > 0 && troops[0].tribeId() != tribeId) {
            throw new IllegalStateException("tribe id mismatch building catalog");
        }
    }

    private static long key(int tribeId, int tIndex) {
        return ((long) tribeId) * 100L + tIndex;
    }

    private TroopSpeedCatalog() {
    }

    /** All catalogued troops, in stable tribe + tIndex order. */
    public static List<TroopSpec> all() {
        return ALL;
    }

    /**
     * Troops the solver iterates over — everything in {@link #ATTACKABLE_ROLES}.
     * Excludes settlers and chiefs (those don't appear in offensive waves).
     */
    public static List<TroopSpec> attackable() {
        return ATTACKABLE;
    }

    /** Lookup by {@code (tribeId, tIndex)}; null when the pair is unknown. */
    public static TroopSpec get(int tribeId, int tIndex) {
        return BY_KEY.get(key(tribeId, tIndex));
    }
}
