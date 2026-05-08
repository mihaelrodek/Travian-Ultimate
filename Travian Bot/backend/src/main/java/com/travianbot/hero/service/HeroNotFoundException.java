package com.travianbot.hero.service;

import com.travianbot.common.error.NotFoundException;

/**
 * Thrown when we ask for a hero for an account that has never been scraped.
 * Mapped to HTTP 404 via {@code GlobalExceptionHandler} so the UI can show a
 * "Scrape now" empty-state instead of a generic error.
 */
public class HeroNotFoundException extends NotFoundException {

    private final Long accountId;

    public HeroNotFoundException(Long accountId) {
        super("Hero not scraped yet for account " + accountId);
        this.accountId = accountId;
    }

    public Long getAccountId() {
        return accountId;
    }
}
