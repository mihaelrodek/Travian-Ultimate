package com.travianbot.bot.scraper;

/**
 * A page fetch returned an anti-bot challenge instead of game content.
 * Deliberately NOT a subtype of {@link NotLoggedInException} — that type
 * triggers the auto-relogin retry path, and re-logging into an unsolved
 * captcha is exactly the wrong move. By the time this is thrown the account
 * has already been paused and its browser released (see
 * {@code CaptchaResponseService}); callers just need to abort their work.
 */
public class CaptchaDetectedException extends RuntimeException {

    private final Long accountId;

    public CaptchaDetectedException(Long accountId, String where) {
        super("Captcha challenge detected on " + where + " for account " + accountId
                + " — automation paused until the next successful login.");
        this.accountId = accountId;
    }

    public Long accountId() {
        return accountId;
    }
}
