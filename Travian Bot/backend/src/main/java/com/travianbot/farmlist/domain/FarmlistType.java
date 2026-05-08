package com.travianbot.farmlist.domain;

/**
 * Rough classification of what a farmlist is attacking.
 * <p>
 * PVE — unowned villages / natars / abandoned farms (raids that are "safe"
 *        and can be looped aggressively).
 * PVP — enemy players (raids that require more thought about timing, waves
 *        and defender presence).
 * <p>
 * The bot uses this tag to pick safer defaults for PVE (short intervals,
 * always-enabled) and stricter ones for PVP (longer intervals, user-confirmed
 * sends). Defaulting to PVE mirrors how most scraped lists are originally
 * authored in-game.
 */
public enum FarmlistType {
    PVE,
    PVP
}
