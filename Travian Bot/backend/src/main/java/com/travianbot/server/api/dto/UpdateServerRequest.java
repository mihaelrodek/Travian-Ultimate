package com.travianbot.server.api.dto;

/**
 * Partial update for {@code PUT /api/servers/{id}}. Every field is nullable:
 * a {@code null} value means "leave this field alone", consistent with the
 * project's other partial-update DTOs.
 */
public record UpdateServerRequest(
        String name,
        String region,
        String gameWorld,
        Boolean active
) {}
