package com.travianbot.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code PUT /api/accounts/{accountId}/proxies/{id}}. Same shape as
 * {@link CreateAccountProxyRequest} but password is special-cased: a {@code null}
 * password means "leave the stored one alone" so the user can edit the label
 * or toggle active without re-typing credentials. Sending an empty string
 * clears the password.
 * <p>
 * The active flag follows the same write-through semantics as create — true
 * activates this row (deactivates siblings), null/false leaves the
 * account-wide active row untouched except when it IS this row and the
 * caller explicitly sets false.
 */
public record UpdateAccountProxyRequest(
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

        Boolean active
) {
}
