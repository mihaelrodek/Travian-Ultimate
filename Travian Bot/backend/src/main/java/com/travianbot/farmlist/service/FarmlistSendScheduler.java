package com.travianbot.farmlist.service;

import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.persistence.FarmlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Periodic tick that walks every enabled farmlist with a {@code sendIntervalMinutes}
 * and fires a send if the interval has elapsed since its last send.
 * <p>
 * Group-override semantics: when two or more lists share the same
 * {@code groupTag}, the <em>most recent</em> send timestamp anywhere in the
 * group acts as the baseline for the whole group. Otherwise rescheduling
 * a list alongside its friends would double-send right after a group-level
 * "Send all" action.
 * <p>
 * The scheduler only issues sends for accounts that currently have an open
 * browser session — anything else is silently skipped. Failures for a single
 * list are logged and do NOT abort the tick, so one jammed account can't
 * starve every other account's rotation.
 */
@Component
public class FarmlistSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(FarmlistSendScheduler.class);

    private final FarmlistRepository farmlistRepository;
    private final FarmlistSendService sendService;
    private final BrowserSessionManager sessionManager;

    public FarmlistSendScheduler(
            FarmlistRepository farmlistRepository,
            FarmlistSendService sendService,
            BrowserSessionManager sessionManager
    ) {
        this.farmlistRepository = farmlistRepository;
        this.sendService = sendService;
        this.sessionManager = sessionManager;
    }

    /**
     * Wake up every minute. Fine-grained timing (send-interval-in-seconds)
     * isn't useful for farmlists — the in-game rally point has its own
     * debouncing anyway.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void tick() {
        List<Farmlist> candidates = loadCandidates();
        if (candidates.isEmpty()) return;

        Instant now = Instant.now();
        Map<GroupKey, Instant> groupBaseline = computeGroupBaselines(candidates);

        List<Farmlist> due = new ArrayList<>();
        for (Farmlist fl : candidates) {
            Instant baseline = resolveBaseline(fl, groupBaseline);
            if (isDue(fl, baseline, now)) due.add(fl);
        }

        if (due.isEmpty()) return;
        log.info("FarmlistSendScheduler: {} list(s) due this tick", due.size());

        for (Farmlist fl : due) {
            Long accountId = fl.getAccount().getId();
            if (!sessionManager.hasOpenSession(accountId)) {
                log.debug("Skipping farmlist {} — no open browser session for account {}", fl.getId(), accountId);
                continue;
            }
            try {
                sendService.sendList(fl.getId());
            } catch (Exception e) {
                log.warn("Scheduler send failed for farmlist {} (account {}): {}",
                        fl.getId(), accountId, e.getMessage());
            }
        }
    }

    /**
     * Pulls only the lists that have a non-null interval, are enabled, and have
     * a travianListId wired up (without it we can't actually address the list
     * on the server, so there's no point scheduling them).
     */
    @Transactional(readOnly = true)
    protected List<Farmlist> loadCandidates() {
        List<Farmlist> all = farmlistRepository.findAllByEnabledTrueAndSendIntervalMinutesIsNotNull();
        List<Farmlist> out = new ArrayList<>(all.size());
        for (Farmlist fl : all) {
            if (fl.getTravianListId() == null) continue;
            out.add(fl);
        }
        return out;
    }

    private static Map<GroupKey, Instant> computeGroupBaselines(List<Farmlist> lists) {
        Map<GroupKey, Instant> out = new HashMap<>();
        for (Farmlist fl : lists) {
            String tag = fl.getGroupTag();
            if (tag == null || tag.isBlank()) continue;
            Instant ts = fl.getLastSentAt();
            if (ts == null) continue;
            GroupKey key = new GroupKey(fl.getAccount().getId(), tag);
            Instant prev = out.get(key);
            if (prev == null || ts.isAfter(prev)) out.put(key, ts);
        }
        return out;
    }

    private static Instant resolveBaseline(Farmlist fl, Map<GroupKey, Instant> groupBaseline) {
        String tag = fl.getGroupTag();
        if (tag != null && !tag.isBlank()) {
            Instant group = groupBaseline.get(new GroupKey(fl.getAccount().getId(), tag));
            if (group != null) {
                Instant own = fl.getLastSentAt();
                if (own == null || group.isAfter(own)) return group;
            }
        }
        return fl.getLastSentAt();
    }

    private static boolean isDue(Farmlist fl, Instant baseline, Instant now) {
        Integer minutes = fl.getSendIntervalMinutes();
        if (minutes == null || minutes <= 0) return false;
        if (baseline == null) return true; // never sent
        return !baseline.plus(Duration.ofMinutes(minutes)).isAfter(now);
    }

    private record GroupKey(Long accountId, String groupTag) {}
}
