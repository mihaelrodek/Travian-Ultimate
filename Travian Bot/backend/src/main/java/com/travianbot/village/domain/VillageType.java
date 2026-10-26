package com.travianbot.village.domain;

/**
 * User-applied role tag for a village. Drives intent — which villages we
 * mean to use offensively, which we defend, which are the late-game WW
 * pieces. The bot doesn't infer this automatically; it's whatever the
 * player decides each village is for.
 * <p>
 * Stored as the enum {@code name()} via {@code @Enumerated(EnumType.STRING)}
 * so a rename here is a schema-level rename — extend with care, prefer
 * adding new values to renaming existing ones.
 * <p>
 * Vocabulary mirrors the community-standard labels used in T4.6 Legends
 * Discord servers and Kirilloid tooling:
 * <ul>
 *   <li>{@link #OFF_HAMMER} — primary offensive village, full hammer build.</li>
 *   <li>{@link #OFF_GHOST} — secondary offensive village (smaller hammer,
 *       fakes / cleaner / decoy role).</li>
 *   <li>{@link #DEFF} — pure defensive village.</li>
 *   <li>{@link #WWK} — wonder-of-the-world keeper (defends the WW village).</li>
 *   <li>{@link #WWR} — wonder-of-the-world runner (delivers resources / artifacts).</li>
 *   <li>{@link #PVE} — farming / passive-income village.</li>
 * </ul>
 */
public enum VillageType {
    OFF_HAMMER,
    OFF_GHOST,
    DEFF,
    WWK,
    WWR,
    PVE
}
