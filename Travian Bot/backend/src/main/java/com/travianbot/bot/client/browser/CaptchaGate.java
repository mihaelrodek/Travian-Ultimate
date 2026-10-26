package com.travianbot.bot.client.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-account "a captcha is waiting for a human" latch.
 * <p>
 * When a challenge is detected the account is paused here and its browser
 * session is released — the release alone stops every scheduler (they all
 * gate on {@code hasOpenSession}), while this latch stops the one thing that
 * could restart them unattended: {@code SessionRecoveryService}'s auto
 * re-login. Logging back in against an unsolved captcha would fail, burn the
 * relogin cooldown, and look exactly like credential-stuffing to Travian's
 * anti-bot.
 * <p>
 * The latch clears on the next <em>successful</em> login (manual or not) —
 * success proves the captcha is gone. In-memory only: after a restart the
 * user is presumably back at the keyboard anyway.
 */
@Component
public class CaptchaGate {

    private static final Logger log = LoggerFactory.getLogger(CaptchaGate.class);

    /** accountId → when/why the account was paused. */
    private final Map<Long, Pause> paused = new ConcurrentHashMap<>();

    public void pause(Long accountId, String reason) {
        if (accountId == null) return;
        paused.put(accountId, new Pause(reason, Instant.now()));
        log.warn("Captcha gate CLOSED for account {} — {}", accountId, reason);
    }

    /** Called after a successful login — the captcha is provably gone. */
    public void clear(Long accountId) {
        if (accountId == null) return;
        if (paused.remove(accountId) != null) {
            log.info("Captcha gate cleared for account {} after successful login", accountId);
        }
    }

    public boolean isPaused(Long accountId) {
        return accountId != null && paused.containsKey(accountId);
    }

    public record Pause(String reason, Instant since) {}
}
