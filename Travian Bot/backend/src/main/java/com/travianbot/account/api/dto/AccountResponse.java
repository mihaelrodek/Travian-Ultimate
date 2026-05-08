package com.travianbot.account.api.dto;

import com.travianbot.account.domain.Account;

import java.time.Instant;

/**
 * Representation of an Account returned to the frontend.
 * <p>
 * {@code password} is included because the account-detail page needs to be
 * able to show the existing value (blurred by default, reveal on click) and
 * prefill the inline edit form. Safe in this project because passwords are
 * already stored in plaintext (single-user, local-only bot) — when the
 * storage story changes to encrypted credentials the field should move to a
 * dedicated {@code /accounts/{id}/credentials} endpoint gated behind a
 * master password.
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
        String password,
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
                account.getPassword(),
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
