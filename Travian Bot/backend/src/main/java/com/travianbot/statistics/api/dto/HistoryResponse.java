package com.travianbot.statistics.api.dto;

import com.travianbot.statistics.domain.StatCategory;

import java.util.List;

/**
 * Response payload for the history endpoint. Each {@link HistoryPointDto} is
 * one hourly-ish capture inside the requested window, sorted oldest → newest
 * so the chart can iterate without reversing.
 *
 * <p>When {@code playerName} is non-null, the points are that one player's
 * numbers over time. When it's null, the points are aggregates across the
 * whole captured top-10 window.
 */
public record HistoryResponse(
        Long accountId,
        StatCategory category,
        int hours,
        String playerName,
        List<HistoryPointDto> points
) {
}
