package com.travianbot.village.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.Tribe;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.account.service.ActiveWindowGate;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.settings.service.GeneralSettingsService;
import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "robot that keeps the queue moving." Wakes up on a fixed cadence
 * (driven off {@link com.travianbot.settings.domain.GeneralSettings#getDefaultCheckIntervalMinutes()}
 * as the per-account interval — it's our general-purpose "how often to check
 * each account" knob since the village-scrape interval moved per-village),
 * walks every village we're already logged into, and tries to start the next
 * {@link QueueStatus#PENDING} row on any village that has capacity to spare.
 *
 * <h3>Why not push?</h3>
 * We could react to scrape events directly, but the queue-starting story is
 * fundamentally a poll: the game's build slot can free up between scrapes
 * (timer ran out without us noticing), Plus subscription status can change,
 * and the scheduler benefits from seeing the whole account at once. A pull
 * tick is also much easier to rate-limit — we can refuse to run more than
 * once per configured interval even if upstream triggers fire quickly.
 *
 * <h3>Capacity decision</h3>
 * Done by {@link BuildQueueCapacity}. The scheduler provides both the scraped
 * counts (from {@code underConstruction} flags) and our own RUNNING row counts,
 * and the helper takes a pessimistic max to avoid racing against the game's
 * rules. Tribe + Plus comes from {@link AccountSettings#getTribe()} and
 * {@link Account#isPlusAccountActive()}.
 *
 * <h3>Failure isolation</h3>
 * One jammed account cannot starve another. We catch per-account and
 * per-village failures, log them, and continue with the next. The scheduler
 * also never logs in on its own — if a session is closed we simply skip.
 */
@Component
public class BuildQueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(BuildQueueScheduler.class);

    /**
     * Minimum time between tick runs regardless of what the cron thread does.
     * Guards against edge cases where the Spring scheduler double-fires under
     * GC pressure or system clock jumps.
     */
    private static final Duration MIN_TICK_SPACING = Duration.ofSeconds(30);

    private final VillageRepository villageRepository;
    private final AccountRepository accountRepository;
    private final AccountSettingsRepository accountSettingsRepository;
    private final ResourceFieldRepository resourceFieldRepository;
    private final BuildingRepository buildingRepository;
    private final BrowserSessionManager sessionManager;
    private final GeneralSettingsService generalSettingsService;
    private final ActiveWindowGate activeWindowGate;
    private final ApplicationContext applicationContext;

    /**
     * Per-account "last tick acted on this account" timestamps. Used together with
     * the per-account scrape interval as a soft rate-limit: we won't try to
     * start a build on the same account more often than once per interval.
     * Concurrent because the Spring scheduler thread reads/writes while tests
     * may invoke {@link #tick()} directly.
     */
    private final Map<Long, Instant> lastRunPerAccount = new ConcurrentHashMap<>();

    private volatile Instant lastGlobalTick = Instant.EPOCH;

    @Autowired
    public BuildQueueScheduler(
            VillageRepository villageRepository,
            AccountRepository accountRepository,
            AccountSettingsRepository accountSettingsRepository,
            ResourceFieldRepository resourceFieldRepository,
            BuildingRepository buildingRepository,
            BrowserSessionManager sessionManager,
            GeneralSettingsService generalSettingsService,
            ActiveWindowGate activeWindowGate,
            ApplicationContext applicationContext
    ) {
        this.villageRepository = villageRepository;
        this.accountRepository = accountRepository;
        this.accountSettingsRepository = accountSettingsRepository;
        this.resourceFieldRepository = resourceFieldRepository;
        this.buildingRepository = buildingRepository;
        this.sessionManager = sessionManager;
        this.generalSettingsService = generalSettingsService;
        this.activeWindowGate = activeWindowGate;
        this.applicationContext = applicationContext;
    }

    /**
     * Wake up every minute. The per-village interval check inside the tick
     * gates the actual work, so a 1-minute wake-up with a 15-minute interval
     * means 14 out of every 15 wake-ups are no-ops — cheap.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void tick() {
        Instant now = Instant.now();
        if (lastGlobalTick.plus(MIN_TICK_SPACING).isAfter(now)) return;
        lastGlobalTick = now;

        int intervalMinutes;
        try {
            // defaultCheckIntervalMinutes is our general "how often to poke an
            // account" knob; village-scrape cadence is now per-village and lives
            // elsewhere (VillageScrapeScheduler), so we no longer couple queue
            // starts to it.
            intervalMinutes = generalSettingsService.get().getDefaultCheckIntervalMinutes();
        } catch (RuntimeException e) {
            log.warn("BuildQueueScheduler: failed to read settings, skipping tick: {}", e.getMessage());
            return;
        }
        if (intervalMinutes <= 0) return;
        Duration interval = Duration.ofMinutes(intervalMinutes);

        // Work per open session only — we never try to log in. Keeps this tick
        // bounded and avoids accidental re-login storms if the session DB is flapping.
        for (Long accountId : sessionIds()) {
            try {
                runForAccount(accountId, now, interval);
            } catch (RuntimeException e) {
                log.warn("BuildQueueScheduler: account {} tick failed: {}", accountId, e.getMessage());
            }
        }
    }

    private List<Long> sessionIds() {
        // BrowserSessionManager doesn't expose its key set. Walk the accounts
        // table and ask per-id — cheap, and matches how scrape/login endpoints
        // look up sessions today.
        List<Account> accounts = accountRepository.findAll();
        List<Long> out = new java.util.ArrayList<>(accounts.size());
        for (Account a : accounts) {
            if (sessionManager.hasOpenSession(a.getId())) out.add(a.getId());
        }
        return out;
    }

    private void runForAccount(Long accountId, Instant now, Duration interval) {
        Instant last = lastRunPerAccount.get(accountId);
        if (last != null && last.plus(interval).isAfter(now)) return;

        // Honour the user's "active hours" preference. Empty schedule = always
        // allowed (the editor's default) so users who haven't configured one
        // aren't accidentally blocked. Recorded as a "looked at this account"
        // tick on the way out so we don't keep re-checking every 60 seconds.
        if (!activeWindowGate.isAllowedNow(accountId)) {
            log.debug("BuildQueueScheduler: account {} outside active window — skipping", accountId);
            lastRunPerAccount.put(accountId, now);
            return;
        }

        // Per-village work must run in its own transaction so one jammed village
        // doesn't poison a whole-account rollback. Go through the self-proxy so
        // @Transactional kicks in — Spring can't intercept a direct this.method() call.
        BuildQueueScheduler self = applicationContext.getBean(BuildQueueScheduler.class);
        List<Village> villages = villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        for (Village v : villages) {
            try {
                self.maybeStartNextOnVillage(accountId, v.getId());
            } catch (RuntimeException e) {
                log.warn("BuildQueueScheduler: village {} on account {} failed: {}",
                        v.getId(), accountId, e.getMessage());
            }
        }
        // The per-interval gate applies to "we looked at this account", not just
        // "we acted" — record the timestamp either way so an empty queue doesn't
        // cause the scheduler to re-enter on the next 60-second tick.
        lastRunPerAccount.put(accountId, now);
    }

    /**
     * Per-village unit of work. Public + {@code @Transactional} so Spring's proxy
     * can give each call its own tx boundary. Returns true if we actually started
     * something — currently only used for debug logging but also convenient for
     * tests.
     */
    @Transactional
    public boolean maybeStartNextOnVillage(Long accountId, Long villageId) {
        Village village = villageRepository.findById(villageId).orElse(null);
        if (village == null) return false;

        // Find the first PENDING row with a slot — the oldest-first order is
        // enforced by {@code BuildingQueueItemRepository.findAllByVillageIdOrderByOrderIndexAscIdAsc}
        // so we honour the user-defined order.
        BuildingQueueItem candidate = null;
        for (BuildingQueueItem it : village.getQueue()) {
            if (it.getStatus() != QueueStatus.PENDING) continue;
            if (it.getSlot() == null) continue; // slot-less rows aren't startable
            candidate = it;
            break;
        }
        if (candidate == null) return false;

        // Resolve capacity. Tribe comes from AccountSettings (user-configured);
        // plus status is scraper-derived and lives on Account.
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return false;
        AccountSettings settings = accountSettingsRepository.findByAccountId(accountId).orElse(null);
        Tribe tribe = settings != null ? settings.getTribe() : null;
        boolean plus = account.isPlusAccountActive();

        List<ResourceField> fields = resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(villageId);
        List<Building> buildings = buildingRepository.findAllByVillageIdOrderBySlotIdAsc(villageId);

        BuildQueueCapacity.ScrapedCounts scraped =
                BuildQueueCapacity.countUnderConstruction(fields, buildings);
        BuildQueueCapacity.RunningCounts running =
                BuildQueueCapacity.countRunning(village.getQueue());

        BuildQueueCapacity.VillageQueueState state = new BuildQueueCapacity.VillageQueueState(
                scraped.fields(),
                scraped.buildings(),
                running.fields(),
                running.buildings()
        );

        BuildQueueCapacity.SlotKind kind = BuildQueueCapacity.SlotKind.forSlot(candidate.getSlot());
        if (!BuildQueueCapacity.canStart(tribe, plus, kind, state)) {
            // Capacity full — perfectly normal; try again next tick.
            return false;
        }

        log.info("BuildQueueScheduler: starting queue item {} on village {} (account {}, tribe={}, plus={})",
                candidate.getId(), villageId, accountId, tribe, plus);
        // Delegate the actual browser work. VillageService.startQueueItem handles
        // the happy path + all the failure modes (no session, slot-less row,
        // no upgrade button) — we don't want to duplicate that logic here.
        VillageService villageService = applicationContext.getBean(VillageService.class);
        villageService.startQueueItem(villageId, candidate.getId());
        return true;
    }

    // Exposed for tests — lets them reset the rate-limit state between runs.
    Map<Long, Instant> rateLimitStateForTests() {
        return lastRunPerAccount;
    }
}
