package com.travianbot.server.api.dto;

/**
 * Lean payload for the frontend's "pivot village" picker on the intel /
 * inactive-search page. Lists every {@link com.travianbot.village.domain.Village}
 * the user owns that sits on this server, with enough context (account
 * + coords) for the dropdown to disambiguate between same-named villages
 * across accounts.
 */
public record PivotVillageDto(
        Long id,
        Long accountId,
        String accountName,
        String villageName,
        int x,
        int y
) {}
