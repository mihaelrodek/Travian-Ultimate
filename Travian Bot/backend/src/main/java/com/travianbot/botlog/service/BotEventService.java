package com.travianbot.botlog.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.botlog.api.dto.BotEventResponse;
import com.travianbot.botlog.domain.BotEvent;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.persistence.BotEventRepository;
import com.travianbot.stream.SseEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Records and serves bot activity events. Write-mostly: every interesting
 * action the bot performs calls {@link #record(Long, Long, String,
 * BotEventOutcome, String)}, and the UI reads back paginated lists.
 * <p>
 * <strong>Why {@code REQUIRES_NEW} on record?</strong> The log is a journal
 * of what happened, not part of the business transaction itself. A scrape
 * that rolls back halfway through should still leave its "SCRAPE — ERROR"
 * trail so the user can see what went wrong. Running the write in its own
 * transaction means the caller's rollback doesn't wipe the event row.
 * <p>
 * <strong>Why a {@code try/catch} around {@code save}?</strong> Logging must
 * never break the thing it's logging. If Postgres is unreachable or the row
 * is malformed, we swallow the exception and fall back to the local logger —
 * the user's build still starts, the scrape still runs.
 */
@Service
public class BotEventService {

    private static final Logger log = LoggerFactory.getLogger(BotEventService.class);

    /**
     * Safety cap on page size — the UI asks for 50 or so, but a rogue client
     * could ask for 10 000 and blow memory. 500 is plenty for any sensible
     * scrollback view and cheap to render.
     */
    private static final int MAX_PAGE_SIZE = 500;

    private final BotEventRepository repository;
    private final AccountRepository accountRepository;
    private final SseEventBroadcaster broadcaster;

    public BotEventService(BotEventRepository repository, AccountRepository accountRepository,
                           SseEventBroadcaster broadcaster) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.broadcaster = broadcaster;
    }

    /**
     * Persist a new event. Returns silently if the account disappeared
     * between when the caller decided to log and when we looked it up
     * (e.g. the user deleted the account during a scrape tick) — the log
     * is best-effort and must not leak exceptions back into callers.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long accountId, Long villageId, String action,
                       BotEventOutcome outcome, String message) {
        if (accountId == null || action == null || outcome == null) {
            // Defensive — a missing field here is a programmer error, but
            // we'd rather log-and-continue than throw and block the caller.
            log.warn("BotEventService.record called with null required fields: accountId={}, action={}, outcome={}",
                    accountId, action, outcome);
            return;
        }
        try {
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null) {
                log.debug("Dropping bot-event {} for missing account {}", action, accountId);
                return;
            }
            String trimmedAction = action.length() > 64 ? action.substring(0, 64) : action;
            BotEvent saved = repository.save(new BotEvent(account, villageId, trimmedAction, outcome, message));
            // Push to any open UI stream once this (REQUIRES_NEW) write commits.
            // DTO is built now, while the entity is attached, then sent after commit.
            broadcaster.broadcastAfterCommit("bot-event", BotEventResponse.from(saved));
        } catch (RuntimeException e) {
            // The log existing is not worth a secondary failure — swallow and trace.
            log.warn("Failed to record bot-event action={} account={} outcome={}: {}",
                    action, accountId, outcome, e.getMessage());
        }
    }

    /**
     * Filtered, paginated list for the Log screen. Every filter is optional
     * and they compose with AND; {@code q} does a case-insensitive substring
     * match against both the message and the action code so the user can
     * type either "overflow" or "SCRAPE" into the same box.
     */
    @Transactional(readOnly = true)
    public Page<BotEvent> list(Long accountId, BotEventOutcome outcome,
                               Instant from, Instant to, String q,
                               int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        List<Specification<BotEvent>> specs = new ArrayList<>();
        if (accountId != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("account").get("id"), accountId));
        }
        if (outcome != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("outcome"), outcome));
        }
        if (from != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            specs.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("message")), like),
                    cb.like(cb.lower(root.get("action")), like)
            ));
        }
        return repository.findAll(Specification.allOf(specs), pageable);
    }

    /**
     * Cleanup: drop this account's log when the account itself is deleted.
     * Runs inside the deletion transaction (MANDATORY) to stay in sync with
     * the rest of the cleanup listeners — see {@link
     * com.travianbot.task.service.BotTaskService#onAccountDeleting} for the
     * same pattern.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
