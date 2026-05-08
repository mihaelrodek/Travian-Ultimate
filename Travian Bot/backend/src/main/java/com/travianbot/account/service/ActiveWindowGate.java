package com.travianbot.account.service;

import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.ActiveWindow;
import com.travianbot.account.persistence.AccountSettingsRepository;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * "Is the bot allowed to act on this account right now?" check, shared by every
 * scheduler that takes an autonomous action on the user's behalf
 * (build queue, scrape, troop training when wired, …).
 * <p>
 * Two rules:
 *   1. If the account has <em>no</em> windows configured, the bot is allowed
 *      to act 24/7 — that's the natural "I haven't restricted anything"
 *      meaning, and matches the schedule editor where an empty list is the
 *      default.
 *   2. If at least one window exists, the current local time (machine local
 *      time, since this is the user's own laptop) must fall inside one of
 *      them on the matching day-of-week.
 * <p>
 * Time math is done in machine local time deliberately. The bot runs on the
 * user's own machine, the schedule editor labels minutes in machine local
 * time, and pulling in a server-time conversion would just confuse the
 * "after midnight rolls over to tomorrow's day-of-week" handling.
 * <p>
 * "Spans midnight" windows (e.g. 22:00 → 03:00) need to be split into two
 * rows by the user — the schedule editor enforces {@code start < end}. We
 * mirror that here: the check is purely {@code start ≤ now < end}.
 */
@Component
public class ActiveWindowGate {

    private final AccountSettingsRepository accountSettingsRepository;

    public ActiveWindowGate(AccountSettingsRepository accountSettingsRepository) {
        this.accountSettingsRepository = accountSettingsRepository;
    }

    /**
     * Convenience: load settings by accountId and run the check. Returns true
     * (allowed) when settings are missing — a brand-new account has no
     * preferences yet, so refusing to act would surprise the user.
     */
    public boolean isAllowedNow(Long accountId) {
        return accountSettingsRepository.findByAccountId(accountId)
                .map(this::isAllowedNow)
                .orElse(true);
    }

    /**
     * Pure form for callers that already have the AccountSettings in hand
     * (avoids a second repo round-trip when a scheduler just loaded it).
     */
    public boolean isAllowedNow(AccountSettings settings) {
        if (settings == null) return true;
        List<ActiveWindow> windows = settings.getActiveWindows();
        if (windows == null || windows.isEmpty()) return true;

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        DayOfWeek today = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        int minutesNow = time.getHour() * 60 + time.getMinute();

        for (ActiveWindow w : windows) {
            if (w.getDayOfWeek() != today) continue;
            if (minutesNow >= w.getStartMinute() && minutesNow < w.getEndMinute()) {
                return true;
            }
        }
        return false;
    }
}
