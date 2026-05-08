package com.travianbot.aianalysis.domain;

/**
 * Resource flavour of an oasis tile on the Travian map. {@code WOOD} / {@code CLAY}
 * / {@code IRON} / {@code CROP} are the standard four; {@code MIXED} is the
 * "wood + crop" / "iron + crop" / "clay + crop" combo tiles, distinct because
 * a mixed oasis raises two stockpiles when occupied.
 * <p>
 * Stored as a string so the (rare) future expansion of map tile types
 * doesn't require a migration.
 */
public enum OasisType {
    WOOD,
    CLAY,
    IRON,
    CROP,
    MIXED,
    UNKNOWN
}
