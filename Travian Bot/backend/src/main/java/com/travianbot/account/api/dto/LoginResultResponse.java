package com.travianbot.account.api.dto;

import java.time.Instant;

/**
 * Outcome of a login attempt, echoed back to the frontend.
 */
public record LoginResultResponse(
        boolean success,
        String status,
        String message,
        Instant timestamp
) {
    public static LoginResultResponse success(String message) {
        return new LoginResultResponse(true, "SUCCESS", message, Instant.now());
    }

    public static LoginResultResponse failure(String status, String message) {
        return new LoginResultResponse(false, status, message, Instant.now());
    }
}
