package com.travianbot.bot.client.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Per-account token-bucket rate limiter for browser actions (roadmap P2.7).
 * <p>
 * Complements {@link HumanDelay}: the delay randomises the gap between two
 * consecutive actions, while this caps the <em>sustained</em> action rate so
 * a burst of scheduled work — several villages coming due in one tick — can't
 * fire dozens of navigations in a few seconds and stand out in Travian's
 * request analytics.
 * <p>
 * Token bucket, one per account:
 * <ul>
 *   <li>Capacity = the per-minute cap, so up to that many actions may burst
 *       through instantly when the account has been idle.</li>
 *   <li>Refills continuously at {@code cap / 60 s}. Once the burst is spent,
 *       actions are paced to the sustained rate.</li>
 * </ul>
 * The cap is read through an {@link IntSupplier} on every call (same live-config
 * pattern as {@link HumanDelay}) so a change in the Settings UI takes effect on
 * the next action without a restart. State is in-memory; a restart resets every
 * bucket to full, which is fine — a just-started bot hasn't sent anything yet.
 * <p>
 * Thread-safety: buckets live in a {@link ConcurrentHashMap} and each bucket's
 * arithmetic is guarded by its own monitor. The blocking sleep happens OUTSIDE
 * that monitor so one account waiting never blocks another's bookkeeping. In
 * practice actions for a single account are already serialized by the caller.
 */
public class ActionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ActionRateLimiter.class);

    /** Clamp bounds mirror the DTO validation, so bad config can't stall or divide-by-zero. */
    private static final int MIN_PER_MINUTE = 1;
    private static final int MAX_PER_MINUTE = 600;
    /** Never sleep longer than this in a single acquire — a backstop against pathological config. */
    private static final long MAX_WAIT_MS = 60_000L;

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final IntSupplier maxPerMinuteSupplier;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public ActionRateLimiter(IntSupplier maxPerMinuteSupplier) {
        this.maxPerMinuteSupplier = maxPerMinuteSupplier;
    }

    /**
     * Block the calling thread just long enough to keep the account under its
     * configured actions-per-minute cap, then return. Interruption is honoured
     * (flag restored, sleep skipped) so a shutdown is never swallowed.
     */
    public void acquire(Long accountId) {
        if (accountId == null) return;
        int perMinute = clampPerMinute(readPerMinute());
        Bucket bucket = buckets.computeIfAbsent(accountId, id -> new Bucket());

        long waitMs;
        synchronized (bucket) {
            waitMs = bucket.reserveOne(perMinute);
        }
        if (waitMs <= 0) return;

        long sleepMs = Math.min(waitMs, MAX_WAIT_MS);
        if (log.isDebugEnabled()) {
            log.debug("Rate limiter: account {} pausing {} ms (cap {}/min)", accountId, sleepMs, perMinute);
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Rate-limit wait interrupted for account {}", accountId);
        }
    }

    private int readPerMinute() {
        try {
            return maxPerMinuteSupplier.getAsInt();
        } catch (RuntimeException e) {
            log.warn("Rate-limit supplier threw {} — falling back to default {}",
                    e.getClass().getSimpleName(), MAX_PER_MINUTE);
            return MAX_PER_MINUTE;
        }
    }

    private static int clampPerMinute(int value) {
        return Math.max(MIN_PER_MINUTE, Math.min(MAX_PER_MINUTE, value));
    }

    /**
     * Single account's bucket. All access is under the enclosing monitor held
     * by {@link #acquire}. {@code tokens} may go slightly negative when an
     * action is reserved against future refill — the returned wait pays that
     * back before the caller actually proceeds.
     */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private boolean initialized;

        /** Reserve one token; return milliseconds the caller must wait before acting (0 if immediate). */
        long reserveOne(int perMinute) {
            double capacity = perMinute;
            double refillPerNano = (double) perMinute / NANOS_PER_MINUTE;
            long now = System.nanoTime();

            if (!initialized) {
                tokens = capacity;       // a fresh account may burst up to the cap immediately
                lastRefillNanos = now;
                initialized = true;
            } else {
                double refilled = (now - lastRefillNanos) * refillPerNano;
                tokens = Math.min(capacity, tokens + refilled);
                lastRefillNanos = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0L;
            }
            // Not enough yet — reserve against future refill and tell the caller how long to wait.
            double deficit = 1.0 - tokens;
            tokens -= 1.0;
            double waitNanos = deficit / refillPerNano;
            return (long) Math.ceil(waitNanos / 1_000_000.0);
        }
    }
}
