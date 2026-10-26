package com.travianbot.village.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.domain.AccountSettings;
import com.travianbot.account.domain.Tribe;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.persistence.AccountSettingsRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.NotLoggedInException;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.village.api.dto.BuildingQueueItemDto;
import com.travianbot.village.api.dto.VillageRequest;
import com.travianbot.village.api.dto.VillageResponse;
import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingQueueItemRepository;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class VillageService {

    private static final Logger log = LoggerFactory.getLogger(VillageService.class);

    private final VillageRepository repository;
    private final AccountRepository accountRepository;
    private final AccountSettingsRepository accountSettingsRepository;
    private final ResourceFieldRepository resourceFieldRepository;
    private final BuildingRepository buildingRepository;
    private final BuildingQueueItemRepository queueItemRepository;
    private final BrowserSessionManager sessionManager;
    private final BotEventService botEventService;
    private final VillageSettingsService villageSettingsService;
    private final VillageTrainConfigService villageTrainConfigService;

    public VillageService(
            VillageRepository repository,
            AccountRepository accountRepository,
            AccountSettingsRepository accountSettingsRepository,
            ResourceFieldRepository resourceFieldRepository,
            BuildingRepository buildingRepository,
            BuildingQueueItemRepository queueItemRepository,
            BrowserSessionManager sessionManager,
            BotEventService botEventService,
            VillageSettingsService villageSettingsService,
            VillageTrainConfigService villageTrainConfigService
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.accountSettingsRepository = accountSettingsRepository;
        this.resourceFieldRepository = resourceFieldRepository;
        this.buildingRepository = buildingRepository;
        this.queueItemRepository = queueItemRepository;
        this.sessionManager = sessionManager;
        this.botEventService = botEventService;
        this.villageSettingsService = villageSettingsService;
        this.villageTrainConfigService = villageTrainConfigService;
    }

    @Transactional(readOnly = true)
    public List<Village> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Village> listForAccount(Long accountId) {
        return repository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
    }

    /**
     * Persists a user-chosen ordering for the Villages sidebar. Accepts the
     * authoritative list of village ids in the new order: position 0 gets
     * {@code orderIndex=0}, etc. Any village ids that belong to the account
     * but aren't mentioned are pushed to the end (preserving their relative
     * order) so the call is safe to make with a partial list.
     * <p>
     * Cross-account ids are refused — the reorder must not reveal villages
     * from another account into this account's ordering.
     */
    public List<Village> reorder(Long accountId, List<Long> orderedVillageIds) {
        if (orderedVillageIds == null) orderedVillageIds = List.of();
        List<Village> all = repository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        java.util.Map<Long, Village> byId = new java.util.HashMap<>();
        for (Village v : all) byId.put(v.getId(), v);

        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (Long id : orderedVillageIds) {
            Village v = byId.get(id);
            if (v == null) continue; // silently skip unknown / cross-account ids
            if (!seen.add(id)) continue; // de-dup caller mistakes
            v.setOrderIndex(idx++);
        }
        // Stragglers (weren't in the incoming list) keep their existing
        // relative order but get fresh indexes at the tail.
        for (Village v : all) {
            if (seen.contains(v.getId())) continue;
            v.setOrderIndex(idx++);
        }
        repository.saveAll(all);
        return repository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
    }

    public List<VillageResponse> reorderDto(Long accountId, List<Long> orderedVillageIds) {
        List<Village> villages = reorder(accountId, orderedVillageIds);
        return villages.stream().map(v -> VillageResponse.from(v, List.of(), List.of())).toList();
    }

    /**
     * List DTOs for the /api/villages endpoint. Lightweight — resource fields and buildings
     * are omitted to keep the list endpoint fast; callers can hit the detail endpoint for
     * per-village scrape data.
     */
    @Transactional(readOnly = true)
    public List<VillageResponse> listDto(Long accountId) {
        List<Village> villages = (accountId == null) ? listAll() : listForAccount(accountId);
        return villages.stream().map(v -> VillageResponse.from(v, List.of(), List.of())).toList();
    }

    @Transactional(readOnly = true)
    public Village get(Long id) {
        return repository.findById(id).orElseThrow(() -> new VillageNotFoundException(id));
    }

    /**
     * Detail DTO — includes resource fields and buildings. Built inside a tx boundary so
     * lazy collections (queue, account.id) can be walked. See the same pattern in
     * {@code AccountSettingsService.getForAccountDto}.
     */
    @Transactional(readOnly = true)
    public VillageResponse getDto(Long id) {
        Village village = get(id);
        return VillageResponse.from(
                village,
                resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(id),
                buildingRepository.findAllByVillageIdOrderBySlotIdAsc(id)
        );
    }

    public Village create(VillageRequest req) {
        if (req.accountId() == null) {
            throw new IllegalArgumentException("accountId is required to create a village");
        }
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException(req.accountId()));
        Village village = new Village(account, req.name().trim(), req.coordinateX(), req.coordinateY());
        village.setCapital(req.capital());
        if (req.population() != null) village.setPopulation(req.population());
        Village saved = repository.save(village);
        // Every village ships with a defaults row so the Settings tab never
        // hits a null on first open. VillageSettingsService also auto-creates
        // lazily, but seeding here keeps the "clean install → defaults visible"
        // story obvious and avoids a phantom INSERT on the first GET.
        villageSettingsService.createDefaults(saved);
        return saved;
    }

    public VillageResponse createDto(VillageRequest req) {
        Village v = create(req);
        return VillageResponse.from(v, List.of(), List.of());
    }

    public Village update(Long id, VillageRequest req) {
        Village village = get(id);
        village.rename(req.name().trim());
        village.moveTo(req.coordinateX(), req.coordinateY());
        village.setCapital(req.capital());
        village.setPopulation(req.population());
        return repository.save(village);
    }

    public VillageResponse updateDto(Long id, VillageRequest req) {
        Village v = update(id, req);
        return getDto(v.getId());
    }

    /**
     * Partial update — manual TS level + village-type tag. Either field may
     * be {@code null} to clear that value. Doesn't touch any of the scraped
     * fields (population, resources, queue, …) so it's safe to call from a
     * UI inline editor without racing the scraper.
     */
    public VillageResponse updateClassificationDto(Long id, Integer tsLevel,
                                                   com.travianbot.village.domain.VillageType type) {
        Village v = repository.findById(id).orElseThrow(() -> new VillageNotFoundException(id));
        v.setTsLevel(tsLevel);
        v.setVillageType(type);
        Village saved = repository.save(v);
        return getDto(saved.getId());
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) throw new VillageNotFoundException(id);
        // Drop children first — they hold FKs to this village. The settings
        // row has a NOT NULL FK on village, so JPA would otherwise refuse the
        // village delete with a constraint error.
        villageSettingsService.deleteByVillageId(id);
        villageTrainConfigService.deleteByVillageId(id);
        resourceFieldRepository.deleteByVillageId(id);
        buildingRepository.deleteByVillageId(id);
        repository.deleteById(id);
    }

    /**
     * Replace the full queue in one shot. Incoming order defines {@code orderIndex};
     * items without an {@code id} are created, items whose id is missing from the
     * incoming list are removed (via orphanRemoval on the collection).
     */
    public Village replaceQueue(Long villageId, List<BuildingQueueItemDto> incoming) {
        Village village = get(villageId);
        List<BuildingQueueItem> existing = village.getQueue();

        // Build a lookup so we can preserve status/createdAt on existing rows.
        var byId = existing.stream()
                .filter(item -> item.getId() != null)
                .collect(java.util.stream.Collectors.toMap(BuildingQueueItem::getId, i -> i));

        List<BuildingQueueItem> next = new java.util.ArrayList<>();
        int order = 0;
        for (BuildingQueueItemDto dto : incoming) {
            BuildingQueueItem item;
            if (dto.id() != null && byId.containsKey(dto.id())) {
                item = byId.get(dto.id());
                item.update(dto.buildingType(), dto.slot(), dto.targetLevel());
                item.setOrderIndex(order);
            } else {
                item = new BuildingQueueItem(village, dto.buildingType(), dto.slot(), dto.targetLevel(), order);
            }
            next.add(item);
            order++;
        }

        existing.clear();
        existing.addAll(next);
        return repository.save(village);
    }

    public VillageResponse replaceQueueDto(Long villageId, List<BuildingQueueItemDto> incoming) {
        replaceQueue(villageId, incoming);
        return getDto(villageId);
    }

    /**
     * Triggers the actual in-game upgrade for a queued item: navigates the
     * browser to {@code /build.php?id=<slot>} and clicks the "Upgrade to
     * level N" button. Marks the queue item RUNNING on success, FAILED
     * otherwise.
     * <p>
     * Preconditions: the queue item must belong to {@code villageId}, must
     * have a non-null {@link BuildingQueueItem#getSlot() slot}, and the
     * owning account must have an open, logged-in browser session (we
     * don't log in on behalf of the user — too much spam potential).
     *
     * @return the refreshed village DTO so the UI can redraw the queue in
     *         one call (matches {@code replaceQueueDto}).
     */
    public VillageResponse startQueueItem(Long villageId, Long queueItemId) {
        BuildingQueueItem item = queueItemRepository.findById(queueItemId)
                .orElseThrow(() -> new QueueItemNotFoundException(queueItemId));
        if (!item.getVillage().getId().equals(villageId)) {
            throw new QueueItemNotFoundException(queueItemId);
        }
        if (item.getSlot() == null) {
            throw new IllegalStateException(
                    "Queue item " + queueItemId + " has no slot assigned — "
                            + "pick a building slot before starting the upgrade.");
        }
        if (item.getStatus() == QueueStatus.RUNNING) {
            // Already started — treat as no-op so the UI Start button is idempotent.
            return getDto(villageId);
        }

        Account account = item.getVillage().getAccount();
        Long accountId = account.getId();
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }

        int slot = item.getSlot();

        // Server-side capacity check: if the village's in-game queue is already
        // full, the browser page will only offer the gold "master builder"
        // button — which the browser client refuses to click. Detecting that
        // here means we short-circuit cleanly (no "FAILED" row, no wasted page
        // load) and the scheduler or user can retry once a slot frees up.
        //
        // Mirrors the tribe/plus-aware capacity rules used by BuildQueueScheduler
        // so the Start button and the scheduler behave identically.
        if (!hasCapacityFor(accountId, item.getVillage(), slot)) {
            log.info("startQueueItem {}: village {} already at build-queue capacity — deferring.",
                    queueItemId, villageId);
            botEventService.record(accountId, villageId, "BUILD_START", BotEventOutcome.WARN,
                    "Deferred " + item.getBuildingType() + " → Lvl " + item.getTargetLevel()
                            + " at slot " + slot + " — village queue at capacity.");
            // Leave the row PENDING so it stays at the top of the queue for the
            // next tick / manual retry. No status flip, no resultMessage churn.
            return getDto(villageId);
        }

        boolean started;
        try {
            started = client.startBuildingUpgrade(account.getServerUrl(), slot);
        } catch (RuntimeException e) {
            log.warn("startBuildingUpgrade threw for item {}: {}", queueItemId, e.getMessage());
            item.markStatus(QueueStatus.FAILED, e.getMessage());
            botEventService.record(accountId, villageId, "BUILD_START", BotEventOutcome.ERROR,
                    "Upgrade click failed for slot " + slot + ": " + e.getMessage());
            return getDto(villageId);
        }

        if (started) {
            item.markStatus(QueueStatus.RUNNING, "Upgrade started at slot " + slot);
            botEventService.record(accountId, villageId, "BUILD_START", BotEventOutcome.OK,
                    "Started " + item.getBuildingType() + " → Lvl " + item.getTargetLevel()
                            + " at slot " + slot);
        } else {
            // Could be master-builder-only (gold refused), missing resources, or
            // max level. We keep a single concise hint — the browser client
            // logs the specific cause.
            String fail = "No free upgrade button — queue full (gold required), missing resources, or max level.";
            item.markStatus(QueueStatus.FAILED, fail);
            botEventService.record(accountId, villageId, "BUILD_START", BotEventOutcome.ERROR,
                    "Could not start " + item.getBuildingType() + " → Lvl " + item.getTargetLevel()
                            + " at slot " + slot + ": " + fail);
        }
        return getDto(villageId);
    }

    /**
     * Pre-flight capacity gate shared with {@code BuildQueueScheduler}. True if
     * the village can accept a new build of the kind implied by {@code slot}
     * right now, given the tribe, Plus subscription, existing RUNNING queue
     * rows, and current {@code underConstruction} flags on the scraped
     * fields/buildings.
     */
    private boolean hasCapacityFor(Long accountId, Village village, int slot) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) return false;
        AccountSettings settings = accountSettingsRepository.findByAccountId(accountId).orElse(null);
        Tribe tribe = settings != null ? settings.getTribe() : null;
        boolean plus = account.isPlusAccountActive();

        List<ResourceField> fields = resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(village.getId());
        List<Building> buildings = buildingRepository.findAllByVillageIdOrderBySlotIdAsc(village.getId());

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
        BuildQueueCapacity.SlotKind kind = BuildQueueCapacity.SlotKind.forSlot(slot);
        return BuildQueueCapacity.canStart(tribe, plus, kind, state);
    }
}
