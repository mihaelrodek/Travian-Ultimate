package com.travianbot.statistics.api.dto;

import java.time.Instant;

/**
 * One point on a stats history chart. We carry only what the chart actually
 * plots: timestamp, a summary rank (best rank visible in this snapshot), and
 * an aggregate/player-specific score. The frontend's hand-rolled SVG chart
 * just plots {@code score} against {@code capturedAt}.
 */
public record HistoryPointDto(
        Instant capturedAt,
        Integer rank,
        Long score
) {
}
