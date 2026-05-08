package com.travianbot.botlog.api.dto;

import com.travianbot.botlog.domain.BotEvent;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Thin wrapper around a JPA {@link Page} that exposes only the fields the
 * frontend pager needs. Using a hand-rolled envelope (instead of
 * serialising {@link Page} directly) shields clients from Spring's internal
 * pagination structure, which includes a lot of extra metadata and is
 * marked as "do not expose over an API" in Spring docs.
 */
public record BotEventPageResponse(
        List<BotEventResponse> items,
        long total,
        int page,
        int size,
        int totalPages
) {
    public static BotEventPageResponse from(Page<BotEvent> page) {
        List<BotEventResponse> items = page.getContent().stream()
                .map(BotEventResponse::from)
                .toList();
        return new BotEventPageResponse(
                items,
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages()
        );
    }
}
