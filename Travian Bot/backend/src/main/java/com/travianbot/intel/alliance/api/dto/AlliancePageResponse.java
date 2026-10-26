package com.travianbot.intel.alliance.api.dto;

import com.travianbot.intel.alliance.domain.MapAlliance;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page envelope for the alliance list — mirrors the shape of
 * {@code InactivePlayerPageResponse} so the frontend pager can be shared.
 */
public record AlliancePageResponse(
        List<AllianceResponse> items,
        long total,
        int page,
        int size,
        int totalPages
) {
    public static AlliancePageResponse from(Page<MapAlliance> page) {
        return new AlliancePageResponse(
                page.getContent().stream().map(AllianceResponse::from).toList(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages()
        );
    }
}
