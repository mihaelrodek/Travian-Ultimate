package com.travianbot.troops.domain;

/**
 * Which in-game training building produces a given troop. Scoped intentionally
 * to the three mainline buildings the queue handles — Great Barracks / Great
 * Stable / Residence/Palace (chiefs + settlers) are out of scope for now.
 * <p>
 * Storing the building alongside the troop code is cheap (it's derivable from
 * the catalog, but caching it on the row means a SQL {@code WHERE building = ?}
 * stays index-friendly instead of needing a giant {@code IN (...)} of codes).
 */
public enum TroopBuilding {
    BARRACKS,
    STABLE,
    WORKSHOP
}
