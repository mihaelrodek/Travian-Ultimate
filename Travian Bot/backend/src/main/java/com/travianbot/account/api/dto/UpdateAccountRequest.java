package com.travianbot.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code PUT /api/accounts/{id}} — lets the user correct credentials
 * or rename an account from the detail page without deleting-and-recreating.
 * <p>
 * Same shape as {@link CreateAccountRequest} minus server-assigned fields.
 */
public record UpdateAccountRequest(
        @NotBlank(message = "serverUrl is required")
        @Pattern(regexp = "^https?://.+", message = "serverUrl must start with http:// or https://")
        @Size(max = 512)
        String serverUrl,

        @NotBlank(message = "username is required")
        @Size(max = 100)
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 255)
        String password,

        /**
         * Display alias shown in the sidebar, lists and URL slug. Required —
         * the URL scheme is {@code /accounts/{nickname}-{server}} so a blank
         * nickname would yield a malformed slug.
         */
        @NotBlank(message = "nickname is required")
        @Size(max = 100)
        String nickname
) {
}
