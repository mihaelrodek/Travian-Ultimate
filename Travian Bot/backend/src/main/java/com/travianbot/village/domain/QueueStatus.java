package com.travianbot.village.domain;

/**
 * Lifecycle of a {@link BuildingQueueItem}.
 * <p>
 * The bot marks items RUNNING while upgrading, DONE after verifying the new level,
 * FAILED if the game refuses (missing resources, prerequisite, etc.), CANCELLED if
 * the user removes an item mid-flight.
 */
public enum QueueStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED
}
