package com.travianbot.enemyintel.service;

import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure helper that diffs two consecutive {@link EnemyProfileSnapshot}s into a
 * human-readable summary string. Stateless and side-effect free so it's
 * trivially unit-testable.
 * <p>
 * Tracked changes, in priority order (the order they appear in the output
 * string when more than one fires):
 * <ol>
 *   <li><b>Hero level</b> — the most interesting signal. A drop means the
 *       hero died; an increase means the player is fighting (and probably
 *       distracted).</li>
 *   <li><b>Village count</b> — they founded / conquered / lost a village.
 *       Conquest losses are the actionable signal.</li>
 *   <li><b>Population</b> — only reported when the delta exceeds 50. A drop
 *       this big means a village was wiped or chiefed; a gain means a build
 *       sprint.</li>
 *   <li><b>Alliance</b> — they joined or left an alliance, or switched.</li>
 *   <li><b>Hero production bonus</b> — they re-rolled their hero attributes.</li>
 * </ol>
 */
@Component
public class EnemyChangeDetector {

    /**
     * Population deltas under this threshold are considered noise — Travian
     * population fluctuates by a few points per resource-field upgrade and
     * we don't want every routine field hit to surface as a "change".
     */
    private static final int POPULATION_NOISE_FLOOR = 50;

    /**
     * Returns a comma-separated human-readable diff, or the empty string if
     * neither snapshot is meaningfully different from the other. A null
     * {@code prev} (first snapshot in history) returns {@code ""} — there's
     * nothing to compare against.
     */
    public String describe(EnemyProfileSnapshot prev, EnemyProfileSnapshot next) {
        if (next == null) return "";
        if (prev == null) return "";

        List<String> parts = new ArrayList<>();

        // Hero level — always reported, even by ±1, because that's the
        // signal we care most about (death/leveled). Direction matters for
        // narration ("Hero 25→24" vs "Hero 24→25").
        Integer prevHero = prev.getHeroLevel();
        Integer nextHero = next.getHeroLevel();
        if (!Objects.equals(prevHero, nextHero) && (prevHero != null || nextHero != null)) {
            parts.add(formatIntDelta("Hero", prevHero, nextHero));
        }

        // Village count — same rule, always reported. Founding a village is
        // ±1 so we don't apply a noise floor.
        Integer prevVil = prev.getVillageCount();
        Integer nextVil = next.getVillageCount();
        if (!Objects.equals(prevVil, nextVil) && (prevVil != null || nextVil != null)) {
            parts.add(formatIntDelta("Villages", prevVil, nextVil));
        }

        // Population — noise-floored. Anything below {@link #POPULATION_NOISE_FLOOR}
        // is treated as no change; this keeps the per-field-upgrade noise off the timeline.
        Integer prevPop = prev.getCurrentPopulation();
        Integer nextPop = next.getCurrentPopulation();
        if (prevPop != null && nextPop != null && Math.abs(nextPop - prevPop) >= POPULATION_NOISE_FLOOR) {
            parts.add(formatIntDelta("Pop", prevPop, nextPop));
        } else if (prevPop == null && nextPop != null) {
            // First-time observation of a population value isn't a "change"
            // so we skip — change detection needs two valid observations.
        }

        // Alliance — both leaving (next null) and joining (prev null) are
        // surfaced because the operational meaning is different ("left X" vs
        // "joined X") and both are worth narrating.
        String prevAlly = prev.getAllianceName();
        String nextAlly = next.getAllianceName();
        if (!Objects.equals(prevAlly, nextAlly)) {
            if (isBlank(prevAlly) && !isBlank(nextAlly)) {
                parts.add("Joined alliance " + nextAlly);
            } else if (!isBlank(prevAlly) && isBlank(nextAlly)) {
                parts.add("Left alliance " + prevAlly);
            } else if (!isBlank(prevAlly) && !isBlank(nextAlly)) {
                parts.add("Alliance " + prevAlly + " -> " + nextAlly);
            }
        }

        // Hero production bonus — re-rolling the hero attributes shifts the
        // resource bonus; this is a signal that the player is actively
        // tuning, not afk.
        Integer prevBonus = prev.getHeroProductionBonus();
        Integer nextBonus = next.getHeroProductionBonus();
        if (!Objects.equals(prevBonus, nextBonus) && (prevBonus != null || nextBonus != null)) {
            parts.add(formatPercentDelta("Hero prod", prevBonus, nextBonus));
        }

        return String.join("; ", parts);
    }

    /**
     * Render an int-valued field's delta in the canonical "Label A->B (+/-N)"
     * format. Nulls render as "?".
     */
    private static String formatIntDelta(String label, Integer prev, Integer next) {
        if (prev == null) return label + " ?->" + next;
        if (next == null) return label + " " + prev + "->?";
        int delta = next - prev;
        String sign = delta > 0 ? "+" : "";
        return label + " " + prev + "->" + next + " (" + sign + delta + ")";
    }

    private static String formatPercentDelta(String label, Integer prev, Integer next) {
        if (prev == null) return label + " ?->" + next + "%";
        if (next == null) return label + " " + prev + "%->?";
        int delta = next - prev;
        String sign = delta > 0 ? "+" : "";
        return label + " " + prev + "%->" + next + "% (" + sign + delta + ")";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
