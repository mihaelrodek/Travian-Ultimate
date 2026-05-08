package com.travianbot.statistics.api.dto;

/**
 * One top-10 row as served to the UI, augmented with deltas against the
 * previous snapshot for the same player name (not the previous snapshot's
 * same-rank row — players swap ranks, so name-based matching is the only
 * thing that gives stable week-over-week numbers).
 * <p>
 * Every top10 table on the source page has the same three-column shape so
 * this DTO matches: rank, player, score. {@code score} semantics are fixed
 * by the enclosing {@code Top10Response.category} — attack pts for PVP/PVE,
 * defense pts for DEFENSE, raided resources for RAIDERS.
 * <p>
 * {@code scoreDelta} and {@code rankDelta} are nullable — {@code null} means
 * "no previous snapshot to compare against" (first capture) or "this player
 * wasn't in the previous top 10". The UI should render nulls as a neutral
 * state, not zero. Rank delta is inverted from raw subtraction: positive =
 * moved up the leaderboard.
 */
public record Top10RowDto(
        int rank,
        String playerName,
        Long score,
        Long scoreDelta,
        Integer rankDelta
) {
}
