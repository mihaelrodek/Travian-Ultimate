package com.travianbot.intel.api.dto;

import com.travianbot.intel.service.InactiveSearchService.SearchResult;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page envelope for the inactive-search results — mirrors the shape used by
 * {@code BotEventPageResponse} so the frontend pager can share its hook.
 */
public record InactivePlayerPageResponse(
        List<InactivePlayerResponse> items,
        long total,
        int page,
        int size,
        int totalPages
) {
    public static InactivePlayerPageResponse from(Page<SearchResult> page) {
        return new InactivePlayerPageResponse(
                page.getContent().stream().map(InactivePlayerResponse::from).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages()
        );
    }
}
