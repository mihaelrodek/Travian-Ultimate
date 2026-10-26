package com.travianbot.account.service;

import com.travianbot.account.api.dto.LoginResultResponse;
import com.travianbot.bot.client.browser.CaptchaGate;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.settings.service.GeneralSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic re-login for scheduled work that finds the browser session gone
 * (Playwright crash, server-side logout, app restart while a session cookie
 * expired). Schedulers call {@link #tryRelogin} instead of silently skipping;
 * on success they retry the operation that was about to be dropped.
 * <p>
 * Guard rails, in order:
 * <ol>
 *   <li><b>Opt-in</b> — no-op unless {@code autoReloginEnabled} is on in
 *       General Settings. Unattended logins can walk straight into a captcha
 *       loop, so the default is off.</li>
 *   <li><b>Cooldown</b> — at most one attempt per account per
 *       {@link #COOLDOWN}, successful or not. A dead account (banned, wrong
 *       password after a change, permanent captcha) must not be hammered
 *       every scheduler tick; Travian counts failed logins.</li>
 *   <li><b>No retry loops</b> — one login attempt per call. The caller
 *       retries its own operation at most once; if the fresh session is
 *       immediately lost again the next attempt waits out the cooldown.</li>
 * </ol>
 * {@link LoginService} already records LOGIN bot-events and fires the
 * captcha / session-lost Discord alerts on failure — this service only adds
 * the AUTO_RELOGIN breadcrumbs so the log shows <i>why</i> a login happened
 * with nobody at the keyboard.
 */
@Service
public class SessionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SessionRecoveryService.class);

    /** Minimum gap between automatic login attempts per account. */
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    private final LoginService loginService;
    private final GeneralSettingsService generalSettingsService;
    private final BotEventService botEventService;
    private final CaptchaGate captchaGate;

    /** Last automatic attempt per account — in-memory, reset on restart by design. */
    private final Map<Long, Instant> lastAttemptByAccount = new ConcurrentHashMap<>();

    public SessionRecoveryService(LoginService loginService,
                                  GeneralSettingsService generalSettingsService,
                                  BotEventService botEventService,
                                  CaptchaGate captchaGate) {
        this.loginService = loginService;
        this.generalSettingsService = generalSettingsService;
        this.botEventService = botEventService;
        this.captchaGate = captchaGate;
    }

    /**
     * Attempt one automatic re-login for the account. Returns true only when
     * a login was attempted AND succeeded — the caller may then retry its
     * operation once. Returns false (without attempting) when the feature is
     * disabled or the account is still inside its cooldown window.
     */
    public boolean tryRelogin(Long accountId, String reason) {
        if (accountId == null) return false;
        if (!generalSettingsService.get().isAutoReloginEnabled()) return false;
        // A pending captcha means the login WILL fail (or worse, look like an
        // automated retry storm to the anti-bot). A human has to solve it —
        // stay quiet until the next successful manual login clears the gate.
        if (captchaGate.isPaused(accountId)) {
            log.debug("Auto-relogin for account {} suppressed — captcha gate is closed", accountId);
            return false;
        }

        Instant now = Instant.now();
        Instant prev = lastAttemptByAccount.get(accountId);
        if (prev != null && Duration.between(prev, now).compareTo(COOLDOWN) < 0) {
            log.debug("Auto-relogin for account {} suppressed — inside cooldown ({} left)",
                    accountId, COOLDOWN.minus(Duration.between(prev, now)));
            return false;
        }
        lastAttemptByAccount.put(accountId, now);

        log.info("Auto-relogin for account {} — {}", accountId, reason);
        botEventService.record(accountId, null, "AUTO_RELOGIN", BotEventOutcome.WARN,
                "Session gone — attempting automatic re-login (" + reason + ")");

        LoginResultResponse result;
        try {
            result = loginService.login(accountId);
        } catch (RuntimeException e) {
            botEventService.record(accountId, null, "AUTO_RELOGIN", BotEventOutcome.ERROR,
                    "Automatic re-login threw: " + e.getMessage());
            return false;
        }

        botEventService.record(accountId, null, "AUTO_RELOGIN",
                result.success() ? BotEventOutcome.OK : BotEventOutcome.ERROR,
                result.success()
                        ? "Automatic re-login succeeded — resuming scheduled work."
                        : "Automatic re-login failed (" + result.status() + "): " + result.message());
        return result.success();
    }
}
