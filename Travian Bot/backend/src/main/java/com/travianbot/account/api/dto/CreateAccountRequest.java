package com.travianbot.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
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
