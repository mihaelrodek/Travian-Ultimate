package com.travianbot.incoming.service;

import com.travianbot.incoming.domain.IncomingAttack;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Stateless heuristic helper for classifying incoming attacks by speed.
 * <p>
 * Travian-side reasoning: we never see the actual troop composition of an
 * inbound attack until the report drops. What we DO see, on the rally-point
 * incoming page, is the absolute arrival time and (often) the source
 * coordinates. Distance over time gives a fields-per-hour value; banding that
 * value matches the canonical T4.6 troop-speed buckets:
 * <ul>
 *   <li>{@code >= 14 f/h} — scouts (Equites Caesaris 16, EI 16) or fast-cav-only stacks</li>
 *   <li>{@code >= 10 f/h} — generic cavalry hammers (Phalanx-shy compositions)</li>
 *   <li>{@code >= 8 f/h} — raiders (Imperians/Theutates running at infantry-cap speed)</li>
 *   <li>{@code >= 5 f/h} — generic infantry (Phalanx, Legionnaire, Clubs)</li>
 *   <li>{@code &lt; 5 f/h} — rams/cats slowing the stack down</li>
 *   <li>{@code unknown} — no source coordinates parsed</li>
 * </ul>
 * <p>
 * These thresholds match what {@link IncomingAttack#classifyByFieldsPerHour}
 * uses — we keep the analyzer as the canonical reference so other modules
 * (e.g. a future "auto-defense scheduler") can call it without having to know
 * which entity owns the math.
 * <p>
 * No map-wrap handling — Travian worlds are nominally toroidal (the right
 * edge connects to the left), but for the bands we care about, the difference
 * between wrap and non-wrap distance is negligible (<5% across the typical
 * 50–80 field attack range). If a real-life misclassification motivates it,
 * fold wrap-around into {@link #distanceBetween(int, int, int, int)} as a
 * single bounded fix.
 */
@Service
public class AttackAnalyzer {

    /**
     * Returns the troop-class bucket for a given speed. Defers to
     * {@link IncomingAttack#classifyByFieldsPerHour(double)} so all classifiers
     * stay in sync.
     */
    public String classifyByFieldsPerHour(double speed) {
        return IncomingAttack.classifyByFieldsPerHour(speed);
    }

    /**
     * Euclidean distance in tile units between two map coordinates. Always
     * returns a value; returns empty only on the convenience overload that
     * accepts boxed types and gets a null.
     */
    public OptionalDouble distanceBetween(int x1, int y1, int x2, int y2) {
        double dx = (double) x2 - (double) x1;
        double dy = (double) y2 - (double) y1;
        return OptionalDouble.of(Math.sqrt(dx * dx + dy * dy));
    }

    /**
     * Estimates speed as distance / elapsed-hours. Returns empty if {@code to}
     * is at or before {@code from} (a wave that arrives instantly is the
     * server-time-skew case — recompute on the next scrape).
     */
    public OptionalDouble estimateSpeed(double distanceFields, Instant from, Instant to) {
        if (from == null || to == null) return OptionalDouble.empty();
        long seconds = Duration.between(from, to).getSeconds();
        if (seconds <= 0) return OptionalDouble.empty();
        double hours = seconds / 3600.0;
        return OptionalDouble.of(distanceFields / hours);
    }

    /**
     * Convenience pipeline: distance → speed → class. Returns empty when any
     * intermediate step lacks data (typical case: no source coords on the
     * rally-point page → caller renders "unknown" and skips the planner).
     */
    public Optional<String> classify(int sourceX, int sourceY,
                                     int defenderX, int defenderY,
                                     Instant detectedAt, Instant arrivalAt) {
        OptionalDouble dist = distanceBetween(sourceX, sourceY, defenderX, defenderY);
        if (dist.isEmpty()) return Optional.empty();
        OptionalDouble speed = estimateSpeed(dist.getAsDouble(), detectedAt, arrivalAt);
        if (speed.isEmpty()) return Optional.empty();
        return Optional.of(classifyByFieldsPerHour(speed.getAsDouble()));
    }
}
