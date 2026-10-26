package com.travianbot.incoming.service;

import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.persistence.IncomingAttackRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Suggests "return-catch" windows — the best moment to launch our own attack
 * at the enemy so we land while their hammer is on its way home with no
 * defense behind it.
 * <p>
 * The math is intentionally simple — this is a planning aid, not an
 * automation:
 * <ol>
 *   <li>Assume the attacker's hammer returns at the same speed it came in,
 *       so {@code attackerArrivesHomeAt = arrivalAt + (arrivalAt - detectedAt)}.</li>
 *   <li>For each of our villages, compute the Euclidean distance to the
 *       attacker's source, divide by {@link #ASSUMED_OUR_SPEED_FIELDS_PER_HOUR}
 *       to get our travel time.</li>
 *   <li>{@code launchAt = attackerArrivesHomeAt - travelDuration - SNIPE_BUFFER}.</li>
 *   <li>Drop suggestions whose {@code launchAt} is already in the past — we
 *       missed the window.</li>
 * </ol>
 * <p>
 * <strong>Why a constant speed?</strong> We don't know what unit the user
 * will send — they decide that themselves based on the troop class shown in
 * the UI. 8 f/h is a sensible "generic infantry hammer" baseline; we
 * document the choice here so changing it is a one-place edit when we
 * later expose a per-village override.
 * <p>
 * Suggestions are only produced for attacks that have both source
 * coordinates parsed; an attack with no coords is silently skipped (the UI
 * lists it in the attacks table but no row appears under return-catch).
 */
@Service
public class ReturnCatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReturnCatchPlanner.class);

    /**
     * Assumed travel speed of the unit the user will counter-attack with, in
     * tile-fields per hour. 8 f/h matches a Roman Imperian / Gaul Theutates
     * Thunder offensive composition — slow enough to be a safe lower bound,
     * fast enough that the suggestion isn't always "too late".
     * <p>
     * If this becomes user-configurable, plumb the override through this
     * planner's constructor — the rest of the math stays as-is.
     */
    public static final double ASSUMED_OUR_SPEED_FIELDS_PER_HOUR = 8.0;

    /**
     * Pad applied to {@code launchAt} so we land slightly after the
     * attacker's wave touches their home village. Lands cleanly into a
     * defenseless village instead of the usual "race the returning troops"
     * coin flip that happens when timing is exact.
     */
    public static final Duration SNIPE_BUFFER = Duration.ofSeconds(30);

    private final IncomingAttackRepository repository;
    private final VillageRepository villageRepository;

    public ReturnCatchPlanner(IncomingAttackRepository repository,
                              VillageRepository villageRepository) {
        this.repository = repository;
        this.villageRepository = villageRepository;
    }

    /**
     * Returns one suggestion per (incoming attack × our village) pair where
     * the planner could compute a feasible launch time. Soonest launch
     * first.
     * <p>
     * Attacks without parsed source coordinates are skipped — we can't
     * compute distance, so we can't suggest a launch window for them.
     */
    @Transactional(readOnly = true)
    public List<ReturnCatchSuggestion> suggestFor(Long accountId) {
        Instant now = Instant.now();
        List<IncomingAttack> incoming =
                repository.findAllByAccountIdAndArrivalAtAfter(accountId, now);
        if (incoming.isEmpty()) return List.of();

        List<Village> ourVillages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        if (ourVillages.isEmpty()) return List.of();

        List<ReturnCatchSuggestion> out = new ArrayList<>();
        for (IncomingAttack atk : incoming) {
            out.addAll(buildSuggestionsForAttack(atk, ourVillages, now));
        }

        out.sort((a, b) -> a.launchAt().compareTo(b.launchAt()));
        return out;
    }

    /**
     * Single-attack convenience used by the import path to fire a Discord
     * notification immediately after a wave is recorded. Returns the soonest
     * viable launch across the account's villages — empty when nothing is
     * launchable (no source coords, all windows already missed, etc).
     * <p>
     * Loads the village list itself so callers don't have to pre-compute it.
     */
    @Transactional(readOnly = true)
    public Optional<ReturnCatchSuggestion> suggestBestForAttack(IncomingAttack atk) {
        if (atk == null || atk.getAccountId() == null) return Optional.empty();
        List<Village> ourVillages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(atk.getAccountId());
        if (ourVillages.isEmpty()) return Optional.empty();

        List<ReturnCatchSuggestion> all = buildSuggestionsForAttack(atk, ourVillages, Instant.now());
        return all.stream().min((a, b) -> a.launchAt().compareTo(b.launchAt()));
    }

    /**
     * Inner per-attack planner. Extracted so both the list-everything path
     * ({@link #suggestFor}) and the single-attack path
     * ({@link #suggestBestForAttack}) share the same math, instead of
     * drifting into two implementations that compute the same thing.
     */
    private List<ReturnCatchSuggestion> buildSuggestionsForAttack(
            IncomingAttack atk, List<Village> ourVillages, Instant now) {
        if (Boolean.TRUE.equals(atk.getAcknowledged())) return List.of();
        if (atk.getSourceX() == null || atk.getSourceY() == null) {
            log.debug("Skipping return-catch for attack {} — no source coords", atk.getId());
            return List.of();
        }
        if (atk.getDetectedAt() == null || atk.getArrivalAt() == null) return List.of();

        long outboundSeconds = Duration.between(atk.getDetectedAt(), atk.getArrivalAt()).getSeconds();
        if (outboundSeconds <= 0) return List.of();
        Instant attackerArrivesHomeAt = atk.getArrivalAt().plusSeconds(outboundSeconds);

        List<ReturnCatchSuggestion> out = new ArrayList<>();
        for (Village v : ourVillages) {
            double dx = (double) atk.getSourceX() - (double) v.getCoordinateX();
            double dy = (double) atk.getSourceY() - (double) v.getCoordinateY();
            double distance = Math.sqrt(dx * dx + dy * dy);
            double travelHours = distance / ASSUMED_OUR_SPEED_FIELDS_PER_HOUR;
            long travelSeconds = Math.round(travelHours * 3600.0);

            Instant launchAt = attackerArrivesHomeAt
                    .minusSeconds(travelSeconds)
                    .minus(SNIPE_BUFFER);

            if (launchAt.isBefore(now)) continue;

            String reason = String.format(
                    "Land ~%ds after attacker arrives home (assumes %.0f f/h, %.1f-field flight).",
                    SNIPE_BUFFER.getSeconds(), ASSUMED_OUR_SPEED_FIELDS_PER_HOUR, distance);

            out.add(new ReturnCatchSuggestion(
                    atk.getId(),
                    v.getId(),
                    v.getName(),
                    (long) atk.getSourceX(),
                    (long) atk.getSourceY(),
                    attackerArrivesHomeAt,
                    launchAt,
                    travelHours,
                    distance,
                    reason
            ));
        }
        return out;
    }

    /**
     * Plain-shape suggestion record. Currency is "what the UI needs to show
     * the user" — IDs for round-tripping back to the attack row, coordinates
     * for the in-game rally-point form, and the two timestamps that drive the
     * countdown clock.
     */
    public record ReturnCatchSuggestion(
            Long attackId,
            Long fromVillageId,
            String fromVillageName,
            Long toX,
            Long toY,
            Instant attackerArrivesHomeAt,
            Instant launchAt,
            double travelHours,
            double distance,
            String reason
    ) {
    }
}
