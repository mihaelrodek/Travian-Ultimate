package com.travianbot.statistics.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks "the statistics page is currently open in the UI for account X".
 * <p>
 * The scheduler only captures for accounts that have pinged recently — this
 * matches the product decision that stats are UI-driven, so we don't want to
 * spin Chromium opening statistics pages if no one is actually looking at
 * them. Heartbeats expire after {@link #TTL} so a closed tab naturally falls
 * out of the set on its own without the frontend having to send an explicit
 * "stop" message.
 */
@Component
public class StatisticsHeartbeatRegistry {

    /**
     * A heartbeat counts for five minutes — long enough that a user who
     * switched tabs briefly won't drop out, short enough that a forgotten
     * tab on a sleeping laptop eventually stops triggering captures.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();

    public void ping(Long accountId) {
        if (accountId == null) return;
        lastSeen.put(accountId, Instant.now());
    }

    public boolean isActive(Long accountId) {
        if (accountId == null) return false;
        Instant ts = lastSeen.get(accountId);
        if (ts == null) return false;
        if (ts.isBefore(Instant.now().minus(TTL))) {
            lastSeen.remove(accountId);
            return false;
        }
        return true;
    }

    /**
     * Called by the scheduler to find out who to capture for this tick.
     * Removes expired entries as a side effect so the map doesn't grow
     * unbounded for accounts that have gone quiet.
     */
    public java.util.Set<Long> activeAccounts() {
        Instant cutoff = Instant.now().minus(TTL);
        lastSeen.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        return new java.util.HashSet<>(lastSeen.keySet());
    }
}
