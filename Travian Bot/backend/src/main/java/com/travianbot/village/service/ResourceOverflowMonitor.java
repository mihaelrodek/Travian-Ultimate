package com.travianbot.village.service;

import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.notification.NotificationDispatcher;
import com.travianbot.village.domain.Village;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks a freshly-scraped village for imminent resource overflow and raises
 * a WARN bot-event (plus optional push alerts) when the warehouse or
 * granary crosses {@link #THRESHOLD}. Called by the scraping pipeline right
 * after stockpile numbers are persisted — never from a schedule of its own,
 * so it can't observe stale values.
 * <p>
 * <strong>Re-alert policy.</strong> A village sitting at 97% would otherwise
 * warn on every scrape tick (as often as every 2 minutes on a hot village).
 * Two rules keep the log readable:
 * <ul>
 *   <li>Cooldown — at most one alert per (village, store) per
 *       {@link #COOLDOWN}.</li>
 *   <li>Hysteresis — dropping back below the threshold clears the state, so
 *       the next crossing alerts immediately instead of waiting out the
 *       cooldown remnant.</li>
 * </ul>
 * State is in-memory only; a restart re-alerts once for anything still full,
 * which is exactly what the user wants to hear about after a restart.
 */
@Service
public class ResourceOverflowMonitor {

    private static final Logger log = LoggerFactory.getLogger(ResourceOverflowMonitor.class);

    /** Alert when a store is at or above this fraction of capacity. */
    static final double THRESHOLD = 0.95;

    /** Minimum gap between repeated alerts for the same (village, store). */
    private static final Duration COOLDOWN = Duration.ofMinutes(60);

    private final BotEventService botEventService;
    private final NotificationDispatcher notifiers;

    /** Last alert per "villageId|store"; entry removed once the store drops below threshold. */
    private final Map<String, Instant> lastAlertByKey = new ConcurrentHashMap<>();

    public ResourceOverflowMonitor(BotEventService botEventService, NotificationDispatcher notifiers) {
        this.botEventService = botEventService;
        this.notifiers = notifiers;
    }

    /**
     * Evaluate one village's stockpile. Never throws — a monitoring hiccup
     * must not fail the scrape that triggered it.
     */
    public void check(Village village) {
        if (village == null || village.getAccount() == null) return;
        try {
            Long accountId = village.getAccount().getId();
            checkStore(accountId, village, "Warehouse",
                    maxOf(village.getCurrentWood(), village.getCurrentClay(), village.getCurrentIron()),
                    village.getWarehouseCapacity());
            checkStore(accountId, village, "Granary",
                    village.getCurrentCrop(), village.getGranaryCapacity());
        } catch (RuntimeException e) {
            log.warn("Resource overflow check failed for village {}: {}", village.getId(), e.getMessage());
        }
    }

    private void checkStore(Long accountId, Village village, String store, Long level, Long capacity) {
        if (level == null || capacity == null || capacity <= 0) return;
        String key = village.getId() + "|" + store;
        double ratio = (double) level / capacity;

        if (ratio < THRESHOLD) {
            // Hysteresis: back under the line → forget, so the next crossing
            // alerts immediately.
            lastAlertByKey.remove(key);
            return;
        }

        Instant now = Instant.now();
        Instant prev = lastAlertByKey.get(key);
        if (prev != null && Duration.between(prev, now).compareTo(COOLDOWN) < 0) return;
        lastAlertByKey.put(key, now);

        String detail = String.format(Locale.ROOT, "%s at %d%% — %,d / %,d%s",
                store, Math.round(ratio * 100), level, capacity,
                store.equals("Warehouse") ? " (fullest of wood/clay/iron)" : " crop");
        botEventService.record(accountId, village.getId(), "RESOURCE_OVERFLOW",
                BotEventOutcome.WARN,
                village.getName() + ": " + detail);
        notifiers.notifyResourceOverflow(accountId, village, store, detail);
    }

    private static Long maxOf(Long a, Long b, Long c) {
        Long max = null;
        for (Long v : new Long[]{a, b, c}) {
            if (v != null && (max == null || v > max)) max = v;
        }
        return max;
    }
}
