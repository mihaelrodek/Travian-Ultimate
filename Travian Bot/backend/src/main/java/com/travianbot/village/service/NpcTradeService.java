package com.travianbot.village.service;

import com.travianbot.account.domain.Account;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.village.domain.Village;
import com.travianbot.village.domain.VillageSettings;
import com.travianbot.village.persistence.VillageSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NPC-trader automation for a single village.
 * <p>
 * Triggering rule (per the user spec):
 * <ul>
 *   <li>Read {@code npcTriggerPct} from {@link VillageSettings} (default 95).</li>
 *   <li>Compare each resource against its capacity — warehouse for wood/clay/iron,
 *       granary for crop. When <i>any</i> resource is at or above
 *       {@code triggerPct × capacity}, NPC fires.</li>
 * </ul>
 * Most users set this to keep crop from overflowing (granary cap × 95%). Wood/clay/iron
 * rarely trip the trigger on their own because production is usually balanced, but
 * we check them too so a wonder-era imbalance still auto-corrects.
 * <p>
 * Target split is driven by {@code npcRatio{Wood,Clay,Iron,Crop}} (must sum to 100 when
 * NPC is enabled, enforced in the settings service). We compute integer targets by
 * multiplying the total stockpile by each ratio, then push the rounding residual onto
 * the resource with the highest ratio so {@code sum(targets) == sum(stock)} exactly —
 * the game refuses the submit if the two sums differ by even one unit.
 * <p>
 * This service is intentionally stateless beyond DB reads: the scraper layer calls
 * {@link #maybeTrade(Long)} right after stamping fresh stockpile values, and the
 * scheduler can call it on its own cadence. Both paths go through
 * {@link BrowserSessionManager} to find an open client — if there's no live session
 * for the account, the call is a no-op.
 */
@Service
public class NpcTradeService {

    private static final Logger log = LoggerFactory.getLogger(NpcTradeService.class);

    private final VillageSettingsRepository settingsRepository;
    private final BrowserSessionManager sessionManager;
    private final BotEventService botEventService;

    public NpcTradeService(VillageSettingsRepository settingsRepository,
                           BrowserSessionManager sessionManager,
                           BotEventService botEventService) {
        this.settingsRepository = settingsRepository;
        this.sessionManager = sessionManager;
        this.botEventService = botEventService;
    }

    /**
     * Check and, if needed, execute an NPC trade for the given village. Caller-friendly:
     * swallows all exceptions and returns {@code false} on any miss (no settings row, NPC
     * disabled, trigger not met, no open session, browser action failed). Return value is
     * {@code true} only when we actually submitted an NPC form — useful for tests and
     * the scheduler's "did anything happen this tick" log line.
     */
    @Transactional
    public boolean maybeTrade(Long villageId) {
        VillageSettings settings = settingsRepository.findByVillageId(villageId).orElse(null);
        if (settings == null) {
            log.debug("NPC skip village {}: no settings row", villageId);
            return false;
        }
        if (!settings.isNpcEnabled() && !settings.isCropWatcherEnabled()) {
            return false;
        }

        Village village = settings.getVillage();
        Long currentWood = village.getCurrentWood();
        Long currentClay = village.getCurrentClay();
        Long currentIron = village.getCurrentIron();
        Long currentCrop = village.getCurrentCrop();
        Long warehouse = village.getWarehouseCapacity();
        Long granary = village.getGranaryCapacity();

        if (currentWood == null || currentClay == null || currentIron == null || currentCrop == null
                || warehouse == null || granary == null) {
            log.debug("NPC skip village {}: stockpile not yet populated", villageId);
            return false;
        }

        long total = currentWood + currentClay + currentIron + currentCrop;
        int[] ratios;
        String reason;

        if (cropWatcherShouldFire(settings, village, currentCrop, granary)) {
            // Crop watcher path: dump everything into crop. Overrides the normal
            // ratio split because the village is in starvation territory and
            // balanced trading isn't the right answer here.
            if (total <= 0) {
                log.debug("Crop watcher skip village {}: total stock is {}, nothing to redistribute", villageId, total);
                return false;
            }
            ratios = new int[] { 0, 0, 0, 100 };
            Long prod = village.getCurrentCropProduction();
            reason = String.format("crop watcher (crop %d/%d ≤ %d%% · production %s/h)",
                    currentCrop, granary, settings.getCropWatcherThresholdPct(),
                    prod == null ? "?" : Long.toString(prod));
        } else if (settings.isNpcEnabled()) {
            int triggerPct = clampPct(settings.getNpcTriggerPct());
            if (!shouldTrigger(currentWood, currentClay, currentIron, currentCrop,
                    warehouse, granary, triggerPct)) {
                return false;
            }
            if (total <= 0) {
                log.debug("NPC skip village {}: total stock is {}, nothing to redistribute", villageId, total);
                return false;
            }
            ratios = normalizeRatios(
                    settings.getNpcRatioWood(),
                    settings.getNpcRatioClay(),
                    settings.getNpcRatioIron(),
                    settings.getNpcRatioCrop()
            );
            reason = String.format("trigger %d%%", triggerPct);
        } else {
            // Crop watcher enabled but the threshold isn't crossed; don't trade.
            return false;
        }

        long[] targets = computeTargets(total, ratios);

        Account account = village.getAccount();
        BrowserTravianClient client = sessionManager.existing(account.getId());
        if (client == null || !client.session().isLoggedIn()) {
            log.debug("NPC skip village {}: no logged-in session for account {}",
                    villageId, account.getId());
            return false;
        }

        // Switch to this village first so the marketplace we open is the right one.
        // The scraper may have left us on a different dorf — can't trust the current view.
        if (village.getTravianVillageId() != null) {
            try {
                client.get(account.getServerUrl() + "/dorf1.php?newdid=" + village.getTravianVillageId());
            } catch (RuntimeException e) {
                log.warn("NPC: switching to village {} failed: {}", villageId, e.getMessage());
                // Proceed anyway — performNpcTrade will navigate to marketplace which
                // may succeed on whatever village is currently active. Worst case it
                // NPCs the wrong village's stock; the trigger check would usually
                // reject that because numbers would be off.
            }
        }

        boolean ok;
        try {
            ok = client.performNpcTrade(
                    account.getServerUrl(),
                    (int) targets[0], (int) targets[1], (int) targets[2], (int) targets[3]
            );
        } catch (RuntimeException e) {
            log.warn("NPC trade threw for village {}: {}", villageId, e.getMessage());
            botEventService.record(account.getId(), villageId, "NPC_TRADE", BotEventOutcome.ERROR,
                    "NPC failed: " + e.getMessage());
            return false;
        }

        if (ok) {
            log.info("NPC traded village {} ({}): stock {}w/{}c/{}i/{}cr → target {}w/{}c/{}i/{}cr [{}]",
                    villageId, village.getName(),
                    currentWood, currentClay, currentIron, currentCrop,
                    targets[0], targets[1], targets[2], targets[3], reason);
            botEventService.record(account.getId(), villageId, "NPC_TRADE", BotEventOutcome.OK,
                    String.format("NPC %s: %d/%d/%d/%d → %d/%d/%d/%d (%s)",
                            village.getName(),
                            currentWood, currentClay, currentIron, currentCrop,
                            targets[0], targets[1], targets[2], targets[3],
                            reason));
        } else {
            botEventService.record(account.getId(), villageId, "NPC_TRADE", BotEventOutcome.WARN,
                    "NPC submit did not succeed (form not found or Plus not active)");
        }
        return ok;
    }

    /**
     * {@code true} when the village is below the crop watcher's threshold AND
     * the village is actually losing crop per hour. The negative-production
     * gate is what the user asked for: a village whose crop is just full from
     * sitting idle (production ≥ 0) doesn't need rescue trading — its crop
     * will recover on its own as upkeep ticks down. Only a starving village
     * (production < 0) is the legitimate target.
     * <p>
     * If we can't read crop production yet (parser missed it, or the village
     * hasn't been scraped since the field was added) we conservatively skip
     * — better to let the granary fill than to NPC away wood/clay/iron when
     * we can't confirm there's a starvation problem.
     * <p>
     * The {@code cropWatcherMinGold} field is recorded but not enforced here —
     * we don't have a reliable scrape for the account-wide gold balance yet,
     * so any check would be lying. Once the gold scraper lands the gate moves
     * here unchanged on the request side.
     */
    static boolean cropWatcherShouldFire(VillageSettings settings, Village village,
                                         long currentCrop, long granaryCapacity) {
        if (!settings.isCropWatcherEnabled()) return false;
        if (granaryCapacity <= 0) return false;
        Long production = village.getCurrentCropProduction();
        if (production == null || production >= 0) return false;
        int thresholdPct = clampPct(settings.getCropWatcherThresholdPct());
        long thresholdAbs = (granaryCapacity * thresholdPct) / 100L;
        return currentCrop <= thresholdAbs;
    }

    // ---------- math helpers (pure, package-private for tests) ----------

    /**
     * {@code true} if any of the four resources is at or above its {@code triggerPct}
     * threshold. Wood/clay/iron share {@code warehouseCapacity}, crop uses {@code granaryCapacity}.
     */
    static boolean shouldTrigger(long wood, long clay, long iron, long crop,
                                 long warehouseCapacity, long granaryCapacity,
                                 int triggerPct) {
        long woodThreshold = (warehouseCapacity * triggerPct) / 100L;
        long clayThreshold = (warehouseCapacity * triggerPct) / 100L;
        long ironThreshold = (warehouseCapacity * triggerPct) / 100L;
        long cropThreshold = (granaryCapacity * triggerPct) / 100L;
        return wood >= woodThreshold
                || clay >= clayThreshold
                || iron >= ironThreshold
                || crop >= cropThreshold;
    }

    /**
     * Split {@code total} by the four ratios. Each target is
     * {@code floor(total × ratio / 100)}, and the rounding residual (0..3 units) is added
     * to the resource with the highest ratio so {@code sum(targets) == total} — the NPC
     * form rejects sums that differ by even one unit.
     */
    static long[] computeTargets(long total, int[] ratios) {
        long[] out = new long[4];
        long sum = ratios[0] + ratios[1] + ratios[2] + ratios[3];
        if (sum <= 0) {
            // All zero — shouldn't happen in practice (settings service enforces
            // sum=100 when npcEnabled), but don't divide-by-zero.
            out[0] = total;
            return out;
        }
        for (int i = 0; i < 4; i++) {
            out[i] = (total * ratios[i]) / sum;
        }
        long residual = total - (out[0] + out[1] + out[2] + out[3]);
        int largestIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (ratios[i] > ratios[largestIdx]) largestIdx = i;
        }
        out[largestIdx] += residual;
        return out;
    }

    /** Clamp to 0..100 in case the column somehow holds a stale out-of-range value. */
    private static int clampPct(int raw) {
        if (raw < 0) return 0;
        if (raw > 100) return 100;
        return raw;
    }

    /**
     * Copy the ratio ints into a local array so we're free to mutate without touching
     * the entity. No normalisation to sum-100 here — {@link #computeTargets} divides by
     * the actual sum so a row that somehow has (25,25,25,24)=99 still works.
     */
    private static int[] normalizeRatios(int wood, int clay, int iron, int crop) {
        return new int[] { Math.max(0, wood), Math.max(0, clay), Math.max(0, iron), Math.max(0, crop) };
    }
}
