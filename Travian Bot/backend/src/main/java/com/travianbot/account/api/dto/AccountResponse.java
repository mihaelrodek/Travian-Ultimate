package com.travianbot.account.api.dto;

import com.travianbot.account.domain.Account;

import java.time.Instant;

/**
 * Representation of an Account returned to the frontend.
 * <p>
 * The Travian login password is deliberately <b>not</b> part of this DTO. The
 * list/detail endpoints are polled routinely (sidebar, detail page, context
 * refresh) so shipping the plaintext password in every payload would spray it
 * across the network and into the DOM for no reason. The account-detail page
 * fetches it lazily from the dedicated {@code GET /accounts/{id}/credentials}
 * endpoint only when the user clicks "Show" or opens the edit form. See
 * {@link AccountCredentialsResponse}.
 * <p>
 * The {@code loggedIn} flag reflects current <i>live</i> browser-session state,
 * not the persisted {@code lastLoginStatus}. It is true only when the backend
 * has an open Playwright session for this account AND that session last reached
 * the ingame marker. The sidebar uses this to toggle the active-account dot red
 * ("active but not logged in") vs. green ("active and live").
 */
public record AccountResponse(
        Long id,
        String serverUrl,
        String username,
        String nickname,
        Instant createdAt,
        Instant lastLoginAt,
        String lastLoginStatus,
        String lastLoginMessage,
        boolean plusAccountActive,
        Instant plusAccountCheckedAt,
        Integer serverUtcOffsetMinutes,
        Instant serverTimeCheckedAt,
        boolean loggedIn
) {
    /**
     * Factory for callers that don't have browser-session information handy.
     * Defaults {@code loggedIn} to {@code false}, which is safe — the sidebar
     * treats unknown as "not logged in" and shows the red dot at worst.
     */
    public static AccountResponse from(Account account) {
        return from(account, false);
    }

    public static AccountResponse from(Account account, boolean loggedIn) {
        return new AccountResponse(
                account.getId(),
                account.getServerUrl(),
                account.getUsername(),
                account.getNickname(),
                account.getCreatedAt(),
                account.getLastLoginAt(),
                account.getLastLoginStatus(),
                account.getLastLoginMessage(),
                account.isPlusAccountActive(),
                account.getPlusAccountCheckedAt(),
                account.getServerUtcOffsetMinutes(),
                account.getServerTimeCheckedAt(),
                loggedIn
        );
    }
}
