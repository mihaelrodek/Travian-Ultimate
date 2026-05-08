package com.travianbot.account.domain;

/**
 * Hero attribute the bot auto-levels when the hero gains a level.
 * {@code OFF} disables auto-level and leaves the decision to the player.
 */
public enum HeroAttribute {
    OFF,
    FIGHTING_STRENGTH,
    OFF_BONUS,
    DEF_BONUS,
    RESOURCES
}
