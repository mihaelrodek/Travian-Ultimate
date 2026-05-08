package com.travianbot.bot.scraper;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.service.NpcTradeService;
import com.travianbot.village.service.QueueReconciliationService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scrapes the currently logged-in browser session for a village's resource fields and buildings,
 * then upserts them in the DB.
 * <p>
 * Two entry points:
 * <ul>
 *   <li>{@link #scrapeCurrentVillage(Long)} — scrape whatever village is active in the browser right now.
 *       Typical use: immediately after login, before the user has picked a village.</li>
 *   <li>{@link #scrapeVillage(Long, Long)} — scrape a specific local-village id. If we've previously
 *       learned its in-game {@code did}, navigates to {@code ?newdid=<did>} first.</li>
 * </ul>
 * <p>
 * Requires {@link BrowserSessionManager} to hold an open, logged-in client for the account. If not,
 * throws {@link NotLoggedInException} — the caller should trigger a login and retry.
 */
@Service
@Transactional
public class ScrapingService {

    private static final Logger log = LoggerFactory.getLogger(ScrapingService.class);

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final VillageRepository villageRepository;
    private final ResourceFieldRepository resourceFieldRepository;
    private final BuildingRepository buildingRepository;
    private final QueueReconciliationService queueReconciliationService;
    private final BotEventService botEventService;
    private final NpcTradeService npcTradeService;

    public ScrapingService(
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            VillageRepository villageRepository,
            ResourceFieldRepository resourceFieldRepository,
            BuildingRepository buildingRepository,
            QueueReconciliationService queueReconciliationService,
            BotEventService botEventService,
            NpcTradeService npcTradeService
    ) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.villageRepository = villageRepository;
        this.resourceFieldRepository = resourceFieldRepository;
        this.buildingRepository = buildingRepository;
        this.queueReconciliationService = queueReconciliationService;
        this.botEventService = botEventService;
        this.npcTradeService = npcTradeService;
    }

    /**
     * Scrape whatever village is currently active in the browser. Used after login when we
     * don't yet know the mapping between local villages and in-game dids.
     */
    public Village scrapeCurrentVillage(Long accountId) {
        BrowserTravianClient client = requireLoggedInClient(accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        try {
            Village v = doScrape(account, client, null);
            botEventService.record(accountId, v.getId(), "SCRAPE_VILLAGE", BotEventOutcome.OK,
                    "Scraped current village: " + v.getName()
                            + " (" + v.getCoordinateX() + "|" + v.getCoordinateY() + ")");
            return v;
        } catch (RuntimeException e) {
            botEventService.record(accountId, null, "SCRAPE_VILLAGE", BotEventOutcome.ERROR,
                    "Current-village scrape failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Scrape a specific local village. Navigates to the village's in-game {@code did} first
     * if we've discovered it; otherwise scrapes whatever is currently selected and asserts
     * the coords match afterwards.
     */
    public Village scrapeVillage(Long accountId, Long villageId) {
        BrowserTravianClient client = requireLoggedInClient(accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        Village village = villageRepository.findById(villageId)
                .orElseThrow(() -> new IllegalArgumentException("Village " + villageId + " not found"));
        if (!village.getAccount().getId().equals(accountId)) {
            throw new IllegalArgumentException("Village " + villageId + " does not belong to account " + accountId);
        }
        try {
            Village v = doScrape(account, client, village);
            botEventService.record(accountId, v.getId(), "SCRAPE_VILLAGE", BotEventOutcome.OK,
                    "Scraped " + v.getName()
                            + " (" + v.getCoordinateX() + "|" + v.getCoordinateY() + ")");
            return v;
        } catch (RuntimeException e) {
            botEventService.record(accountId, villageId, "SCRAPE_VILLAGE", BotEventOutcome.ERROR,
                    "Scrape failed for village " + villageId + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * Sequentially scrape every village on the account. We scrape the current village
     * first (which also upserts the sidebar list, discovering any village we haven't
     * tracked yet), then loop through the full list and {@code ?newdid=}-navigate to
     * each one. A single failure is logged and skipped so one 404 village doesn't
     * abort the rest of the refresh.
     *
     * @return number of villages we successfully refreshed (including the initial current-village scrape).
     */
    public int scrapeAllVillages(Long accountId) {
        BrowserTravianClient client = requireLoggedInClient(accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        int scraped = 0;
        int failed = 0;
        // Prime the sidebar discovery by scraping whatever is currently active.
        doScrape(account, client, null);
        scraped++;
        List<Village> villages = villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        for (Village v : villages) {
            if (v.getTravianVillageId() == null) {
                // Can't target it without a did; the sidebar scrape above may have filled this in
                // for some — anything still null we just skip silently.
                continue;
            }
            try {
                doScrape(account, client, v);
                scraped++;
            } catch (RuntimeException e) {
                log.warn("Failed to scrape village {} for account {}: {}", v.getId(), accountId, e.getMessage());
                failed++;
            }
        }
        BotEventOutcome outcome = failed == 0 ? BotEventOutcome.OK : BotEventOutcome.WARN;
        String msg = "Reload all: " + scraped + " villages refreshed"
                + (failed > 0 ? ", " + failed + " failed" : "");
        botEventService.record(accountId, null, "SCRAPE_ALL_VILLAGES", outcome, msg);
        return scraped;
    }

    // ---------- internals ----------

    private BrowserTravianClient requireLoggedInClient(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null) {
            throw new NotLoggedInException(accountId);
        }
        if (!client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }
        return client;
    }

    /**
     * Core scrape flow. If {@code target} is non-null and has a travianVillageId, we navigate
     * to it first; otherwise we scrape whatever is active.
     */
    private Village doScrape(Account account, BrowserTravianClient client, Village target) {
        String base = account.getServerUrl();
        String dorf1Url = base + "/dorf1.php"
                + (target != null && target.getTravianVillageId() != null
                        ? "?newdid=" + target.getTravianVillageId()
                        : "");
        log.info("Scraping dorf1 for account {}: {}", account.getId(), dorf1Url);
        String dorf1Html = client.get(dorf1Url);

        ParsedVillageHeader header = VillageHeaderParser.parse(dorf1Html);
        List<ParsedResourceField> fields = VillagePageParser.parseDorf1(dorf1Html);
        Document dorf1Doc = Jsoup.parse(dorf1Html);

        // Plus-account detection runs off the same dorf1 payload — the premium strip
        // is rendered on every page. We re-record the flag on every scrape so it
        // tracks subscription expiry without needing its own scheduler.
        try {
            boolean plus = PlusAccountParser.isPlusActive(dorf1Doc);
            if (plus != account.isPlusAccountActive()) {
                log.info("Plus-account status for account {} changed: {} -> {}",
                        account.getId(), account.isPlusAccountActive(), plus);
            }
            account.recordPlusStatus(plus);
        } catch (RuntimeException e) {
            // Never let a parser bug break a scrape — plus detection is a nice-to-have.
            log.debug("Plus-account detection skipped for account {}: {}", account.getId(), e.getMessage());
        }

        // Server-clock offset — scraped from the footer "(UTC +01:00)" string.
        // We refresh on every scrape so DST changes propagate without a manual
        // toggle. Parser returns null when the offset marker isn't present,
        // which we still stamp so "checked" != "never checked".
        try {
            Integer offset = ServerTimeParser.parseOffsetMinutes(dorf1Doc);
            if (offset != null
                    && (account.getServerUtcOffsetMinutes() == null
                            || !offset.equals(account.getServerUtcOffsetMinutes()))) {
                log.info("Server UTC offset for account {} changed: {} -> {} minutes",
                        account.getId(), account.getServerUtcOffsetMinutes(), offset);
            }
            account.recordServerUtcOffset(offset);
        } catch (RuntimeException e) {
            log.debug("Server-time offset scrape skipped for account {}: {}", account.getId(), e.getMessage());
        }
        accountRepository.save(account);

        // Parse the right-hand switcher for names + groups of every village on the account.
        // Even when we're scraping only one village, the sidebar has everyone else's identity
        // — a free opportunity to keep the village list in sync with what's in-game.
        List<ParsedSidebarVillage> sidebar = SidebarVillageListParser.parse(dorf1Html);
        upsertSidebarVillages(account, sidebar);

        String dorf2Url = base + "/dorf2.php";
        log.info("Scraping dorf2 for account {}: {}", account.getId(), dorf2Url);
        String dorf2Html = client.get(dorf2Url);
        List<ParsedBuilding> buildings = VillagePageParser.parseDorf2(dorf2Html);

        Village village = upsertVillage(account, target, header);
        applySidebarMetadata(village, sidebar);
        replaceResourceFields(village, fields);
        replaceBuildings(village, buildings);

        // Stockpile + capacity from the `var resources = {...}` JS blob. Parsed
        // non-fatally — if the blob is missing, the scrape still succeeds with
        // fields + buildings populated; only the NPC trigger is skipped downstream.
        try {
            VillageResourcesParser.parse(dorf1Html).ifPresent(s ->
                    village.setStockpile(
                            s.wood(), s.clay(), s.iron(), s.crop(),
                            s.warehouseCapacity(), s.granaryCapacity(),
                            s.cropProduction()
                    )
            );
        } catch (RuntimeException e) {
            log.warn("Stockpile parse failed for village {}: {}", village.getId(), e.getMessage());
        }
        // Reconcile queue state AFTER the fields/buildings are refreshed — that's when
        // RUNNING rows can be decided against the latest underConstruction flags and
        // levels. Doing it here keeps scheduler ticks simple: they can trust queue
        // statuses to mirror what the game shows on the most recent page render.
        try {
            queueReconciliationService.reconcile(village.getId());
        } catch (RuntimeException e) {
            // Never let a reconciliation hiccup fail the whole scrape — worst case the
            // queue UI shows a stale RUNNING row until the next scrape cleans it up.
            log.warn("Queue reconciliation failed for village {}: {}", village.getId(), e.getMessage());
        }
        // Pull finish-at timestamps off the #buildingList sidebar and attach them
        // to the matching RUNNING queue rows. Done after reconciliation so we're
        // only stamping rows the reconciler agrees are actually running.
        try {
            List<InGameBuildQueueParser.Entry> inGame = InGameBuildQueueParser.parse(dorf1Doc, Instant.now());
            // dorf2 also shows the sidebar — fall back to it if dorf1 didn't list
            // anything, since some skins only render the queue on dorf2.
            if (inGame.isEmpty()) {
                inGame = InGameBuildQueueParser.parse(Jsoup.parse(dorf2Html), Instant.now());
            }
            applyFinishesAt(village, inGame);
        } catch (RuntimeException e) {
            log.warn("In-game queue scrape failed for village {}: {}", village.getId(), e.getMessage());
        }
        village.markScraped();
        villageRepository.save(village);

        log.info("Scrape complete for village {} (travianId={}): {} fields, {} buildings, {} sidebar entries",
                village.getId(), village.getTravianVillageId(), fields.size(), buildings.size(), sidebar.size());

        // Fire the NPC check after we've persisted fresh stockpile values. Best-effort
        // and isolated: if this throws, the scrape is already recorded and the next tick
        // will retry. The browser navigation inside performNpcTrade reuses the same
        // logged-in session so there's no extra cost when NPC is disabled.
        try {
            npcTradeService.maybeTrade(village.getId());
        } catch (RuntimeException e) {
            log.warn("NPC trigger failed for village {}: {}", village.getId(), e.getMessage());
        }
        return village;
    }

    /**
     * Best-effort upsert of every village the sidebar knows about. We never delete villages
     * from here — the sidebar can be hidden or partially rendered during page transitions, and
     * we'd rather keep a stale row than drop one the user still cares about. Dedicated cleanup
     * happens only when the account is deleted.
     */
    private void upsertSidebarVillages(Account account, List<ParsedSidebarVillage> entries) {
        for (ParsedSidebarVillage entry : entries) {
            if (entry.travianVillageId() == null) continue;
            Village existing = villageRepository
                    .findFirstByAccountIdAndTravianVillageId(account.getId(), entry.travianVillageId())
                    .orElse(null);
            if (existing == null && entry.coordinateX() != null && entry.coordinateY() != null) {
                existing = villageRepository
                        .findFirstByAccountIdAndCoordinateXAndCoordinateY(
                                account.getId(), entry.coordinateX(), entry.coordinateY())
                        .orElse(null);
            }
            if (existing == null) {
                // Refuse to materialise a village without real coordinates. A (0|0) sentinel
                // row was previously inserted whenever the sidebar parser couldn't resolve
                // the coords; those rows cluttered the villages UI and were always invalid.
                // If we truly don't know where a village lives, skip it this round — the next
                // scrape (when the sidebar fully renders) will fill the coords in and the
                // row can be created then.
                if (entry.coordinateX() == null || entry.coordinateY() == null) {
                    log.debug("Skipping sidebar entry without coords for account {} (travianId={}, name={})",
                            account.getId(), entry.travianVillageId(), entry.name());
                    continue;
                }
                String name = entry.name() != null ? entry.name() : "Unknown";
                existing = new Village(account, name, entry.coordinateX(), entry.coordinateY());
                existing.setTravianVillageId(entry.travianVillageId());
                villageRepository.save(existing);
                log.info("Discovered village via sidebar for account {}: {} ({},{})",
                        account.getId(), name, entry.coordinateX(), entry.coordinateY());
            }
            applySidebarEntry(existing, entry);
            villageRepository.save(existing);
        }
    }

    /**
     * Make sure the village row we're about to return already has the latest name/group from
     * the sidebar, even if the header parser didn't see them. Idempotent — re-applying on the
     * same snapshot is a no-op.
     */
    private void applySidebarMetadata(Village village, List<ParsedSidebarVillage> entries) {
        if (village.getTravianVillageId() == null) return;
        for (ParsedSidebarVillage entry : entries) {
            if (entry.travianVillageId() == null) continue;
            if (entry.travianVillageId().equals(village.getTravianVillageId())) {
                applySidebarEntry(village, entry);
                return;
            }
        }
    }

    private void applySidebarEntry(Village village, ParsedSidebarVillage entry) {
        if (entry.name() != null && !entry.name().equals(village.getName())) {
            village.rename(entry.name());
        }
        if (entry.coordinateX() != null && entry.coordinateY() != null
                && (village.getCoordinateX() != entry.coordinateX()
                    || village.getCoordinateY() != entry.coordinateY())) {
            village.moveTo(entry.coordinateX(), entry.coordinateY());
        }
        // Group can legitimately go null (user removed it in-game) — pass it through as-is.
        if (!java.util.Objects.equals(entry.groupName(), village.getGroupName())) {
            village.setGroupName(entry.groupName());
        }
    }

    /**
     * Resolves the local {@link Village} row for what was just scraped. Priority:
     * <ol>
     *   <li>If we were given an explicit {@code target}, use it (and update its id/name/coords from the header).</li>
     *   <li>Else look up by (accountId, travianVillageId) from the header.</li>
     *   <li>Else look up by (accountId, x, y) from the header.</li>
     *   <li>Else create a new village row.</li>
     * </ol>
     * If the header is completely empty we fall through with whatever the caller passed us.
     */
    private Village upsertVillage(Account account, Village target, ParsedVillageHeader header) {
        Village village = target;
        if (village == null && header != null && header.travianVillageId() != null) {
            village = villageRepository.findFirstByAccountIdAndTravianVillageId(
                    account.getId(), header.travianVillageId()).orElse(null);
        }
        if (village == null && header != null && header.coordinateX() != null && header.coordinateY() != null) {
            village = villageRepository.findFirstByAccountIdAndCoordinateXAndCoordinateY(
                    account.getId(), header.coordinateX(), header.coordinateY()).orElse(null);
        }
        if (village == null) {
            // If the header didn't yield coordinates, don't create a (0|0) sentinel — the
            // old behaviour produced phantom villages that the UI later had to filter out.
            // Bail cleanly; the caller can retry once the page is fully rendered.
            if (header == null || header.coordinateX() == null || header.coordinateY() == null) {
                throw new IllegalStateException(
                        "Could not determine village coordinates from page header for account "
                                + account.getId() + " — refusing to create a placeholder village.");
            }
            String name = header.name() != null ? header.name() : "Unknown";
            village = new Village(account, name, header.coordinateX(), header.coordinateY());
            villageRepository.save(village);
            log.info("Discovered new village for account {}: {} ({},{})",
                    account.getId(), name, header.coordinateX(), header.coordinateY());
        } else if (header != null) {
            if (header.name() != null && !header.name().equals(village.getName())) {
                village.rename(header.name());
            }
            if (header.coordinateX() != null && header.coordinateY() != null
                    && (village.getCoordinateX() != header.coordinateX()
                        || village.getCoordinateY() != header.coordinateY())) {
                village.moveTo(header.coordinateX(), header.coordinateY());
            }
        }
        if (header != null && header.travianVillageId() != null
                && !java.util.Objects.equals(header.travianVillageId(), village.getTravianVillageId())) {
            village.setTravianVillageId(header.travianVillageId());
        }
        return village;
    }

    private void replaceResourceFields(Village village, List<ParsedResourceField> parsed) {
        List<ResourceField> existing = resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(village.getId());
        Map<Integer, ResourceField> bySlot = new HashMap<>();
        for (ResourceField f : existing) bySlot.put(f.getSlotId(), f);

        for (ParsedResourceField p : parsed) {
            ResourceField f = bySlot.remove(p.slotId());
            if (f == null) {
                f = new ResourceField(village, p.slotId(), p.gid(), p.type(), p.level());
                f.refresh(p.gid(), p.type(), p.level(), p.underConstruction());
            } else {
                f.refresh(p.gid(), p.type(), p.level(), p.underConstruction());
            }
            resourceFieldRepository.save(f);
        }
        // Anything left in bySlot was removed in-game (rare, but possible if we rescan a moved village).
        for (ResourceField stale : bySlot.values()) {
            resourceFieldRepository.delete(stale);
        }
    }

    /**
     * Attach each scraped in-game countdown to a matching RUNNING queue row so
     * the UI can render "done at HH:MM" without re-scraping.
     * <p>
     * Matching strategy (in priority order):
     * <ol>
     *   <li>Normalised name + target level — the strongest signal (handles
     *       name drift like "Iron Mine" vs. "IRON_MINE").</li>
     *   <li>Positional fallback — we zip remaining unmatched entries with
     *       remaining RUNNING rows in creation order. Safe because both lists
     *       represent "what is building right now" and should agree.</li>
     * </ol>
     * RUNNING rows that don't get a match have their {@code finishesAt}
     * cleared — the sidebar is authoritative, so a missing entry means the
     * build is no longer in flight (or about to be reconciled to DONE).
     */
    private void applyFinishesAt(Village village, List<InGameBuildQueueParser.Entry> entries) {
        List<BuildingQueueItem> running = new ArrayList<>();
        for (BuildingQueueItem it : village.getQueue()) {
            if (it.getStatus() == QueueStatus.RUNNING) running.add(it);
        }
        if (running.isEmpty()) {
            // Nothing in the DB to match. Still clear any stale finishesAt off
            // PENDING rows that might have carried over from a prior session —
            // a PENDING row has no business with a future finish stamp.
            for (BuildingQueueItem it : village.getQueue()) {
                if (it.getFinishesAt() != null && it.getStatus() != QueueStatus.DONE) {
                    it.setFinishesAt(null);
                }
            }
            return;
        }
        List<InGameBuildQueueParser.Entry> remaining = new ArrayList<>(entries);

        // Pass 1 — exact-ish match on normalised name + target level.
        for (BuildingQueueItem item : running) {
            String normType = normaliseName(item.getBuildingType());
            InGameBuildQueueParser.Entry hit = null;
            for (InGameBuildQueueParser.Entry e : remaining) {
                if (!normaliseName(e.buildingName()).equals(normType)) continue;
                if (e.targetLevel() != null && e.targetLevel() != item.getTargetLevel()) continue;
                hit = e;
                break;
            }
            if (hit != null) {
                item.setFinishesAt(hit.finishesAt());
                remaining.remove(hit);
            }
        }

        // Pass 2 — positional fallback for whatever didn't match by name.
        java.util.Iterator<InGameBuildQueueParser.Entry> leftovers = remaining.iterator();
        for (BuildingQueueItem item : running) {
            if (item.getFinishesAt() != null) continue;
            if (!leftovers.hasNext()) break;
            InGameBuildQueueParser.Entry next = leftovers.next();
            item.setFinishesAt(next.finishesAt());
        }

        // Pass 3 — clear stale timestamps. Any RUNNING row we still couldn't
        // match had its build vanish from the sidebar, so the prior timestamp
        // is no longer accurate.
        for (BuildingQueueItem item : running) {
            if (item.getFinishesAt() != null && entries.isEmpty()) {
                item.setFinishesAt(null);
            }
        }
    }

    /**
     * Collapses a building-type string to a comparable form so "IRON_MINE",
     * "Iron Mine", and "iron-mine" all match. Keeps letters and digits only.
     */
    private static String normaliseName(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void replaceBuildings(Village village, List<ParsedBuilding> parsed) {
        List<Building> existing = buildingRepository.findAllByVillageIdOrderBySlotIdAsc(village.getId());
        Map<Integer, Building> bySlot = new HashMap<>();
        for (Building b : existing) bySlot.put(b.getSlotId(), b);

        for (ParsedBuilding p : parsed) {
            Building b = bySlot.remove(p.slotId());
            if (b == null) {
                b = new Building(village, p.slotId(), p.gid(), p.name(), p.level());
                b.refresh(p.gid(), p.name(), p.level(), p.underConstruction());
            } else {
                b.refresh(p.gid(), p.name(), p.level(), p.underConstruction());
            }
            buildingRepository.save(b);
        }
        for (Building stale : bySlot.values()) {
            buildingRepository.delete(stale);
        }
    }
}
