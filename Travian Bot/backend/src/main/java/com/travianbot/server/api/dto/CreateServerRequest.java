package com.travianbot.server.api.dto;

/**
 * Request body for {@code POST /api/servers}. {@code region} and
 * {@code gameWorld} are nullable — purely cosmetic metadata for the picker.
 */
public record CreateServerRequest(
        String url,
        String name,
        String region,
        String gameWorld
) {}
