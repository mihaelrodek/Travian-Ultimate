package com.travianbot.server.api.dto;

import com.travianbot.server.domain.UpcomingServer;

import java.time.Instant;

/**
 * Wire shape for {@link UpcomingServer}. Excludes {@code rawJson} and the
 * Hibernate audit timestamps the UI doesn't need — keeps the payload tiny
 * and avoids leaking debug blobs into the browser.
 */
public record UpcomingServerResponse(
        Long id,
        String calendarId,
        String region,
        String worldName,
        Integer speed,
        Instant scheduledStartAt,
        String status,
        String sourceUrl,
        String inferredServerUrl,
        Long linkedServerId,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
    public static UpcomingServerResponse from(UpcomingServer u) {
        return new UpcomingServerResponse(
                u.getId(),
                u.getCalendarId(),
                u.getRegion(),
                u.getWorldName(),
                u.getSpeed(),
                u.getScheduledStartAt(),
                u.getStatus(),
                u.getSourceUrl(),
                u.getInferredServerUrl(),
                u.getLinkedServerId(),
                u.getFirstSeenAt(),
                u.getLastSeenAt()
        );
    }
}
