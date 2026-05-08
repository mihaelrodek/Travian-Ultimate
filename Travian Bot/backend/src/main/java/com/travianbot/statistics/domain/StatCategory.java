package com.travianbot.statistics.domain;

/**
 * The four weekly rankings Travian shows on the single
 * {@code /statistics/player/top10} page. One page ⇒ four parsed tables ⇒ one
 * snapshot per category per capture, so the UI can tab between them and
 * graph each one independently.
 *
 * <p>There is deliberately no URL axis on this enum — we only ever scrape the
 * top10 page. The category only disambiguates <i>which of the four tables on
 * that page</i> a given snapshot came from.
 *
 * <ul>
 *   <li>{@link #PVP} — "PvP of the week" (first {@code #top10_offs} table,
 *       attack points earned against other players).</li>
 *   <li>{@link #PVE} — "PvE of the week" (second {@code #top10_offs} table,
 *       attack points earned against Natars / bandits).</li>
 *   <li>{@link #DEFENSE} — "Defenders of the week"
 *       ({@code #top10_defs}, defense points earned).</li>
 *   <li>{@link #RAIDERS} — "Robbers of the week"
 *       ({@code #top10_raiders}, resources raided).</li>
 * </ul>
 *
 * <p>⚠️ The enum values changed in Phase H (were POPULATION/OFFENSIVE/
 * DEFENSIVE/HEROES). Legacy rows carrying the old strings are cleaned up on
 * startup by {@code StatCategoryLegacyCleanup} so Hibernate doesn't throw an
 * {@code IllegalArgumentException} reading them.
 */
public enum StatCategory {
    PVP,
    PVE,
    DEFENSE,
    RAIDERS
}
