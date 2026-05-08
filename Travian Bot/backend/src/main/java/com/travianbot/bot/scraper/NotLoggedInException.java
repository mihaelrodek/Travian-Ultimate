package com.travianbot.bot.scraper;

/**
 * Raised when a scrape is requested for an account with no open browser session.
 * Caller is expected to trigger a login first and retry.
 */
public class NotLoggedInException extends RuntimeException {
    private final Long accountId;

    public NotLoggedInException(Long accountId) {
        super("Account " + accountId + " has no open browser session — log in first.");
        this.accountId = accountId;
    }

    public Long accountId() {
        return accountId;
    }
}
