package com.travianbot.hero.domain;

/**
 * Which resource the hero's production bonus is currently routed to.
 * {@code ALL_RESOURCES} is the "split between all four" setting.
 */
public enum HeroProductionType {
    WOOD,
    CLAY,
    IRON,
    CROP,
    ALL_RESOURCES,
    UNKNOWN
}
