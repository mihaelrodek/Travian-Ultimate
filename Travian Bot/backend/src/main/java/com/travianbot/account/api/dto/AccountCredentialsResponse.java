package com.travianbot.account.api.dto;

import com.travianbot.account.domain.Account;

/**
 * The account's Travian login password, served <b>only</b> from the dedicated
 * {@code GET /accounts/{id}/credentials} endpoint. Kept out of
 * {@link AccountResponse} so the routinely-polled list/detail payloads never
 * carry the plaintext secret. The frontend requests this lazily — on an
 * explicit "Show" click or when opening the edit form — so the password stays
 * off the wire (and out of the DOM) until the user actually asks to see it.
 * <p>
 * NOTE — this is still plaintext over HTTP. Acceptable only because this is a
 * single-user, localhost-only bot. When credential storage moves to encrypted
 * at rest, this endpoint is the single choke point to gate behind a
 * user-supplied master password.
 */
public record AccountCredentialsResponse(String password) {
    public static AccountCredentialsResponse from(Account account) {
        return new AccountCredentialsResponse(account.getPassword());
    }
}
