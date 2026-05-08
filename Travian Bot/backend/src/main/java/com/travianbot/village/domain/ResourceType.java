package com.travianbot.village.domain;

/**
 * The four resource tile types found in the 18 outside-village slots (dorf1.php).
 * <p>
 * gid mapping (T4.6 Legends):
 * <ul>
 *   <li>1 → {@link #WOOD}</li>
 *   <li>2 → {@link #CLAY}</li>
 *   <li>3 → {@link #IRON}</li>
 *   <li>4 → {@link #CROP}</li>
 * </ul>
 */
public enum ResourceType {
    WOOD,
    CLAY,
    IRON,
    CROP;

    /**
     * Map a Travian resource-field gid (1..4) to a {@link ResourceType}.
     * Returns {@code null} for unknown gids so the caller can decide the fallback.
     */
    public static ResourceType fromGid(int gid) {
        return switch (gid) {
            case 1 -> WOOD;
            case 2 -> CLAY;
            case 3 -> IRON;
            case 4 -> CROP;
            default -> null;
        };
    }
}
