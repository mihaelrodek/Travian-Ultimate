package com.travianbot.bot.client.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Inserts a uniform-random sleep before (or between) browser actions.
 * <p>
 * This is the single most important anti-detection primitive. Travian's server analytics flag
 * accounts whose action timings form tight, regular distributions — a real person's clicks are
 * bursty and uneven. Every click/type/press/navigate issued by {@link BrowserTravianClient} goes
 * through one of these helpers.
 * <p>
 * The range is read through a {@link Supplier} on every call rather than cached, so changes made
 * in the Settings UI take effect immediately without restarting the bot. The supplier is expected
 * to be cheap (the settings service has its own transactional read) — if that ever becomes a
 * bottleneck, wrap it in a short-lived memoizer.
 * <p>
 * Defaults / safety: the supplier can return any non-negative pair; we clamp to {@code min >= 0}
 * and {@code max >= min} so bad config never causes a {@link IllegalArgumentException} from
 * {@link ThreadLocalRandom#nextLong(long, long)}. A zero range means "no sleep" — useful for
 * tests.
 */
public class HumanDelay {

    private static final Logger log = LoggerFactory.getLogger(HumanDelay.class);

    /** Absolute ceiling (ms) applied even if settings returns a larger value — prevents a typo in the UI from stalling the bot for minutes. */
    private static final long MAX_SLEEP_MS = 30_000L;

    private final Supplier<Range> rangeSupplier;

    public HumanDelay(Supplier<Range> rangeSupplier) {
        this.rangeSupplier = rangeSupplier;
    }

    /**
     * Sleeps a random duration within the configured range before the caller performs a browser
     * action. Safe to call from any thread; treats {@link InterruptedException} as "wake up, skip
     * the sleep, restore the interrupt flag" — we never want humanization to swallow a shutdown.
     */
    public void beforeAction() {
        long sleepMs = nextSleepMs();
        if (sleepMs <= 0) return;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Human-delay sleep interrupted after {} ms request", sleepMs);
        }
    }

    /**
     * Exposed for callers that already have a try/catch around a Playwright action — lets them
     * inject the humanization without an extra method hop.
     */
    public long nextSleepMs() {
        Range r;
        try {
            r = rangeSupplier.get();
        } catch (RuntimeException e) {
            log.warn("Click-delay supplier threw {}: {} — falling back to zero sleep", e.getClass().getSimpleName(), e.getMessage());
            return 0L;
        }
        if (r == null) return 0L;
        long min = Math.max(0L, r.minMs());
        long max = Math.min(MAX_SLEEP_MS, Math.max(min, r.maxMs()));
        long chosen;
        if (max <= min) {
            chosen = min;
        } else {
            // nextLong is exclusive on upper bound; add 1 so the configured max is reachable.
            chosen = ThreadLocalRandom.current().nextLong(min, max + 1);
        }
        // DEBUG rather than INFO — this fires on every click/type, easily hundreds of lines
        // per minute. The user can enable `logging.level.com.travianbot.bot.client.browser.HumanDelay=DEBUG`
        // when they need to verify that the UI-configured range is actually reaching the
        // browser layer (common support question: "is my click delay working?"). Off by
        // default keeps normal logs quiet.
        if (log.isDebugEnabled()) {
            log.debug("Human-delay pick: {} ms in [{}, {}]", chosen, min, max);
        }
        return chosen;
    }

    /**
     * Immutable pair of bounds in milliseconds. Kept as its own type (instead of Settings'
     * {@code ClickDelayRange}) to avoid dragging the settings module as a dependency of the bot
     * client — this class sits under {@code bot.client} and must stay cycle-free.
     */
    public record Range(int minMs, int maxMs) {
    }
}
