package com.travianbot.statistics.api.dto;

import com.travianbot.statistics.domain.StatCategory;

import java.time.Instant;
import java.util.List;

/**
 * Top-10 payload for the statistics UI.
 * <p>
 * {@code capturedAt} / {@code previousCapturedAt} are exposed so the UI can
 * show a "compared against X hours ago" subtitle and render the age of the
 * data the user is looking at.
 */
public record Top10Response(
        Long accountId,
        StatCategory category,
        String serverUrl,
        Instant capturedAt,
        Instant previousCapturedAt,
        List<Top10RowDto> rows
) {
    /** Empty response used when no snapshot has ever been captured for the account+category. */
    public static Top10Response empty(Long accountId, StatCategory category) {
        return new Top10Response(accountId, category, null, null, null, List.of());
    }
}
