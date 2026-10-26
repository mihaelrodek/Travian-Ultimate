package com.travianbot.bot.scraper;

import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.CaptchaGate;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.notification.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One place for the full "we hit a captcha" reaction (roadmap P2.8):
 * <ol>
 *   <li>Close the {@link CaptchaGate} so auto-relogin won't walk back into
 *       the challenge.</li>
 *   <li>Release the browser session — every scheduler gates on
 *       {@code hasOpenSession}, so this alone pauses all automation for the
 *       account without touching any scheduler code.</li>
 *   <li>Record an ERROR bot-event so the Log screen shows what stopped the
 *       bot and when.</li>
 *   <li>Fire the (already existing) Discord captcha alert so the user gets
 *       paged to come solve it.</li>
 * </ol>
 * Everything is best-effort — a notification failure must not mask the
 * original detection.
 */
@Service
public class CaptchaResponseService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaResponseService.class);

    private final CaptchaGate captchaGate;
    private final BrowserSessionManager sessionManager;
    private final BotEventService botEventService;
    private final NotificationDispatcher notifiers;

    public CaptchaResponseService(CaptchaGate captchaGate,
                                  BrowserSessionManager sessionManager,
                                  BotEventService botEventService,
                                  NotificationDispatcher notifiers) {
        this.captchaGate = captchaGate;
        this.sessionManager = sessionManager;
        this.botEventService = botEventService;
        this.notifiers = notifiers;
    }

    /**
     * React to a detected challenge. {@code where} is a short human label of
     * the page that served it (e.g. "dorf1" or "dorf2").
     */
    public void handleDetected(Long accountId, Long villageId, String where) {
        String reason = "Captcha challenge detected on " + where;
        captchaGate.pause(accountId, reason);

        try {
            sessionManager.release(accountId);
        } catch (RuntimeException e) {
            log.warn("Failed to release browser after captcha for account {}: {}",
                    accountId, e.getMessage());
        }

        botEventService.record(accountId, villageId, "CAPTCHA", BotEventOutcome.ERROR,
                reason + ". Automation paused; browser closed. Log in manually to solve it and resume.");

        try {
            notifiers.notifyCaptcha(accountId, reason
                    + " — automation is paused until you log in and solve it.");
        } catch (RuntimeException e) {
            log.debug("Captcha notify failed for account {}: {}", accountId, e.toString());
        }
    }
}
