package com.travianbot.server.discovery;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds candidate Travian server URLs for the one-shot discovery probe.
 * <p>
 * <strong>Modern Travian Legends (2024+) URL scheme.</strong> Travian
 * consolidated its country-specific lobbies into <em>continental communities</em>
 * a couple of years ago. The current canonical layout is
 * {@code https://ts{TS_NUMBER}.x{SPEED}.{COMMUNITY}.travian.com}, where the
 * {@code TS_NUMBER} is tied to the speed multiplier in a fixed scheme:
 * <ul>
 *   <li><b>x1</b> (annual special, 1-year worlds) — {@code ts1..ts10}</li>
 *   <li><b>x2</b> (accelerated) — {@code ts20, ts21}</li>
 *   <li><b>x3</b> (accelerated) — {@code ts30, ts31}</li>
 *   <li><b>x5</b> (accelerated) — {@code ts50, ts51}</li>
 *   <li><b>x10</b> (sprint, ~1 month) — {@code ts100}</li>
 * </ul>
 * Example URLs:
 * <pre>
 *   https://ts5.x1.arabics.travian.com      (x1 world #5 on the Arabics continent)
 *   https://ts100.x10.arabics.travian.com   (the lone x10 sprint world)
 *   https://ts5.x1.czsk.travian.com         (Czech/Slovak community special)
 * </pre>
 *
 * <p><strong>Communities.</strong> Five continental + a handful of
 * "community-special" worlds. Travian does not publish a machine-readable
 * index, so the list is hand-curated and trimmed when the team retires
 * a market.
 *
 * <p><strong>Tides of Conquest expansion.</strong> Some communities expose
 * an additional {@code https://tx3.{COMMUNITY}.travian.com} host for the
 * Tides of Conquest variant; we probe one per community as a low-cost
 * extra. Kingdoms-style hosts ({@code https://com{N}.kingdoms.com}) are
 * intentionally NOT generated — they're a different engine and serve a
 * SQL dump our intel pipeline can't consume.
 *
 * <p>Pure utility: no Spring dependencies, but registered as a
 * {@code @Component} so it can be constructor-injected.
 */
@Component
public class CandidateUrlGenerator {

    /**
     * Active continental + community-special worlds. Order matters only for
     * log determinism; the discovery probe doesn't care.
     */
    static final List<String> COMMUNITIES = List.of(
            // Continents (the modern bulk).
            "international",
            "europe",
            "america",
            "asia",
            "arabics",
            // Community-special hosts (small, long-running, language-specific).
            "czsk"
    );

    /**
     * Per-speed list of TS numbers Travian actually uses. The number is tied
     * to the speed multiplier — see the class javadoc.
     */
    static final List<Integer> X1_NUMBERS  = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    static final List<Integer> X2_NUMBERS  = List.of(20, 21);
    static final List<Integer> X3_NUMBERS  = List.of(30, 31);
    static final List<Integer> X5_NUMBERS  = List.of(50, 51);
    static final List<Integer> X10_NUMBERS = List.of(100);

    /**
     * Generate every candidate URL. Returns a List (insertion-ordered, dedup'd
     * via a {@link LinkedHashSet} pass) so callers can iterate predictably.
     */
    public List<String> generateAll() {
        Set<String> out = new LinkedHashSet<>();
        for (String community : COMMUNITIES) {
            addSpeedGroup(out, community, 1,  X1_NUMBERS);
            addSpeedGroup(out, community, 2,  X2_NUMBERS);
            addSpeedGroup(out, community, 3,  X3_NUMBERS);
            addSpeedGroup(out, community, 5,  X5_NUMBERS);
            addSpeedGroup(out, community, 10, X10_NUMBERS);

            // Tides of Conquest single-host variant — one probe per community.
            out.add(canonical("https://tx3." + community + ".travian.com"));
        }
        return new ArrayList<>(out);
    }

    private static void addSpeedGroup(Set<String> out, String community, int speed, List<Integer> tsNumbers) {
        for (int ts : tsNumbers) {
            out.add(canonical("https://ts" + ts + ".x" + speed + "." + community + ".travian.com"));
        }
    }

    /**
     * Strips a trailing slash if present. Mirrors {@code Server.canonicalUrl}
     * so the lookup in {@code ServerRepository.findByUrl} matches without
     * re-normalising.
     */
    private static String canonical(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
