package com.travianbot.farmlist.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.NotLoggedInException;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.persistence.FarmlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Issues the rally-point "send all" action for one or more farmlists.
 * <p>
 * Two entry points:
 * <ul>
 *   <li>{@link #sendList(Long)} — send a single farmlist now. Used by the
 *       frontend "Send all now" button and by {@link FarmlistSendScheduler}.</li>
 *   <li>{@link #sendAllForAccount(Long)} — send every enabled farmlist the
 *       account owns. Used by the Accounts page bulk button.</li>
 * </ul>
 * <p>
 * Preconditions checked on every call:
 * <ol>
 *   <li>The Farmlist must be enabled.</li>
 *   <li>The Farmlist must have a {@code travianListId} — without it we can't
 *       address the list on the server. Scraper fills this on first run.</li>
 *   <li>A browser session must exist for the account and be logged in.</li>
 * </ol>
 * Any failure is logged and the transaction rolls back the {@code markAllSent}
 * call for that list so the scheduler will retry on the next tick.
 */
@Service
@Transactional
public class FarmlistSendService {

    private static final Logger log = LoggerFactory.getLogger(FarmlistSendService.class);

    private final FarmlistRepository farmlistRepository;
    private final AccountRepository accountRepository;
    private final BrowserSessionManager sessionManager;
    private final BotEventService botEventService;

    public FarmlistSendService(
            FarmlistRepository farmlistRepository,
            AccountRepository accountRepository,
            BrowserSessionManager sessionManager,
            BotEventService botEventService
    ) {
        this.farmlistRepository = farmlistRepository;
        this.accountRepository = accountRepository;
        this.sessionManager = sessionManager;
        this.botEventService = botEventService;
    }

    /**
     * Sends a single farmlist right now. Returns the number of targets the
     * browser confirmed as submitted, or 0 if the list has no enabled targets.
     * Throws if the list doesn't exist, isn't enabled, or has no travianListId.
     */
    public int sendList(Long farmlistId) {
        Farmlist fl = farmlistRepository.findById(farmlistId)
                .orElseThrow(() -> new FarmlistNotFoundException(farmlistId));
        return sendListInternal(fl);
    }

    /**
     * Sends every enabled farmlist that has a discovered travianListId for
     * the given account. Returns the number of lists that were actually
     * handed to the browser.
     */
    public int sendAllForAccount(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        List<Farmlist> lists = farmlistRepository.findAllByAccountIdOrderByCreatedAtAsc(account.getId());
        int sent = 0;
        int failed = 0;
        for (Farmlist fl : lists) {
            if (!fl.isEnabled()) continue;
            if (fl.getTravianListId() == null) {
                log.debug("Skipping farmlist {} — no travianListId yet (rescrape first)", fl.getId());
                continue;
            }
            try {
                sendListInternal(fl);
                sent++;
            } catch (Exception e) {
                // Keep going — one bad list shouldn't block the rest.
                log.warn("Send failed for farmlist {} on account {}: {}", fl.getId(), accountId, e.getMessage());
                failed++;
            }
        }
        BotEventOutcome outcome = failed == 0 ? BotEventOutcome.OK : BotEventOutcome.WARN;
        botEventService.record(accountId, null, "FARMLIST_SEND_ALL", outcome,
                "Sent " + sent + " farmlist" + (sent == 1 ? "" : "s")
                        + (failed > 0 ? ", " + failed + " failed" : ""));
        return sent;
    }

    private int sendListInternal(Farmlist fl) {
        if (!fl.isEnabled()) {
            throw new IllegalStateException("Farmlist " + fl.getId() + " is not enabled");
        }
        if (fl.getTravianListId() == null) {
            throw new IllegalStateException(
                    "Farmlist " + fl.getId() + " has no travianListId — scrape the rally point first"
            );
        }

        Long accountId = fl.getAccount().getId();
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }

        String serverUrl = fl.getAccount().getServerUrl();
        int count;
        try {
            count = client.sendFarmlist(serverUrl, fl.getTravianListId());
        } catch (RuntimeException e) {
            botEventService.record(accountId, null, "FARMLIST_SEND", BotEventOutcome.ERROR,
                    "Farmlist \"" + fl.getName() + "\" (#" + fl.getId() + ") failed: " + e.getMessage());
            throw e;
        }
        fl.markAllSent();
        farmlistRepository.save(fl);
        log.info("Sent farmlist {} ({} targets) for account {}", fl.getId(), count, accountId);
        botEventService.record(accountId, null, "FARMLIST_SEND", BotEventOutcome.OK,
                "Sent farmlist \"" + fl.getName() + "\" — " + count + " target"
                        + (count == 1 ? "" : "s"));
        return count;
    }
}
