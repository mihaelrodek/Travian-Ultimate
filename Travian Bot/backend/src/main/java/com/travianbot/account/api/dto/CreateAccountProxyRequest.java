package com.travianbot.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/accounts/{accountId}/proxies}. The URL must be a
 * full scheme://host[:port] so Playwright can consume it verbatim; the label,
 * username, password and active flag are optional.
 * <p>
 * Playwright accepts {@code http://}, {@code https://}, {@code socks4://},
 * and {@code socks5://}, so the regex intentionally allows any lowercase
 * scheme rather than hard-coding the list.
 */
public record CreateAccountProxyRequest(
        @Size(max = 100)
        String label,

        @NotBlank(message = "url is required")
        @Pattern(regexp = "^[a-z][a-z0-9+\\-.]*://.+", message = "url must start with a scheme like http:// or socks5://")
        @Size(max = 512)
        String url,

        @Size(max = 200)
        String username,

        @Size(max = 512)
        String password,

        /**
         * If true, this proxy becomes the account's active one — the service
         * clears the flag on any sibling row. Null/false leaves the active
         * proxy unchanged (or leaves the account with no active proxy if it
         * had none before).
         */
        Boolean active
) {
}
