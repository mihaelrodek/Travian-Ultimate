package com.travianbot.statistics.scraper;

/**
 * One ranking row scraped from a single category table on the
 * {@code /statistics/player/top10} page.
 * <p>
 * The page exposes four tables (PvP / PvE / Defenders / Raiders), all with an
 * identical three-column shape: rank, player, value. We therefore keep the
 * parsed row equally thin. The caller knows which {@link com.travianbot.statistics.domain.StatCategory}
 * the row belongs to (it asked for that category) and interprets {@link #score}
 * accordingly (attack pts / defense pts / raided resources).
 * <p>
 * {@code score} is boxed because certain server skins occasionally render a
 * row without a parseable value; we prefer persisting the rank + name with
 * {@code null} over dropping the whole row.
 */
public record ParsedTop10Row(int rank, String playerName, Long score) {
}
