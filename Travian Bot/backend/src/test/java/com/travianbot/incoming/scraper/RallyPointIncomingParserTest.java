package com.travianbot.incoming.scraper;

import com.travianbot.incoming.scraper.RallyPointIncomingParser.ParsedIncomingAttack;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure-function checks for the rally-point parser. Bidi marks are written as
 * explicit unicode escape literals so the test survives any source-file
 * encoding round-trip.
 */
class RallyPointIncomingParserTest {

    private static final Instant NOW = Instant.parse("2026-06-25T09:12:45Z");
    /** LRO — left-to-right override. Browser injects this before signed nums. */
    private static final String LRO = "‭";
    /** PDF — pop directional formatting. Closes the LRO scope. */
    private static final String PDF = "‬";
    /** U+2212 — typographic minus, the browser also swaps in for ASCII hyphen. */
    private static final String UMINUS = "−";

    /**
     * Reproduces the exact paste reported in the field. Two things must work:
     * <ol>
     *   <li>Strip bidi marks so the coord regex actually matches.</li>
     *   <li>Pick the BARE {@code "Natars -78|150"} as the attacker source —
     *       NOT the in-parens {@code (-104|54)} which is the defender's own
     *       village. The structural anchor here is the "Arrival" line; we
     *       walk back from it and prefer the bare-coord form.</li>
     * </ol>
     */
    @Test
    void parsesRealRallyPointPasteWithBidiMarks() {
        // (−104|54) wrapped exactly like the browser produces it.
        String dirtyCoord = LRO + "(" + LRO + UMINUS + LRO + "104" + PDF + PDF
                + "|" + LRO + "54" + PDF + ")" + PDF;

        String paste = ""
                + "Incoming troops (1)\n"
                + "A01 Raphaelo\tmark attackNinja Kornjaca raids Natars -78|150\n"
                + dirtyCoord + "\tPhalanx\tSwordsman\tPathfinder\tTheutates Thunder"
                + "\tDruidrider\tHaeduan\tRam\tTrebuchet\tChieftain\tSettler\tHero\n"
                + "Troops\t0\t1\t0\t0\t0\t0\t0\t1\t0\t0\t0\n"
                + "Arrival\tin 13:17:15 hrs.at 23:30:00\n";

        List<ParsedIncomingAttack> parsed =
                RallyPointIncomingParser.parsePastedContent(paste, NOW);

        assertThat(parsed).hasSize(1);
        ParsedIncomingAttack wave = parsed.get(0);
        // The attacker came from Natars (-78, 150). The defender's own
        // coord (-104, 54) appears in parens on the next line — we must
        // NOT mistake it for the source.
        assertThat(wave.sourceX()).isEqualTo(-78);
        assertThat(wave.sourceY()).isEqualTo(150);
        assertThat(wave.sourceVillageName()).isEqualTo("Natars");
        assertThat(wave.attackerName()).isEqualTo("Ninja Kornjaca");
        assertThat(wave.arrivalAt())
                .isEqualTo(NOW.plusSeconds(13L * 3600 + 17L * 60 + 15));
        assertThat(wave.isAttack()).isTrue();
    }

    /**
     * Spot-check distance math against the user's reported numbers. The bare
     * coord parse must produce a source that yields the textbook Euclidean
     * value between {@code (-78, 150)} and {@code (-104, 54)}.
     */
    @Test
    void parsedSourceProducesCorrectEuclideanDistance() {
        String paste = ""
                + "A01 Raphaelo\tmark attackNinja Kornjaca raids Natars -78|150\n"
                + "(-104|54)\tPhalanx\n"
                + "Troops\t0\t1\n"
                + "Arrival\tin 1:00:00\n";

        List<ParsedIncomingAttack> parsed =
                RallyPointIncomingParser.parsePastedContent(paste, NOW);
        assertThat(parsed).hasSize(1);
        int sx = parsed.get(0).sourceX();
        int sy = parsed.get(0).sourceY();

        // Defender is the user's A01 Raphaelo at (-104, 54).
        int dx = -104 - sx;
        int dy = 54 - sy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        assertThat(dist).isCloseTo(99.46, within(0.05));
    }

    @Test
    void stripsAllSupportedBidiMarks() {
        // LRM, RLM, LRE, RLE, PDF, LRO, RLO, LRI, RLI, FSI, PDI, BOM
        String dirty = "‎‏‪‫‬‭‮"
                + "⁦⁧⁨⁩﻿hello";
        assertThat(RallyPointIncomingParser.stripBidiMarks(dirty)).isEqualTo("hello");
    }

    /**
     * "Server time: 10:12:45 (UTC +01:00)" header in the paste must drive
     * the arrival math — not {@code callerNow}. Otherwise a 5-minute pause
     * between copy and paste shifts every recorded arrival 5 minutes later.
     */
    @Test
    void anchorsArrivalToServerTimeHeaderNotCallerNow() {
        // Server time printed at the top of the paste: 10:12:45 UTC+1
        //   → absolute server-now = 09:12:45 UTC
        // Caller paste was submitted 30 min later (caller now = 09:42:45 UTC).
        // Header-anchored arrival = 09:12:45 + 13:17:15 = 22:30:00 UTC
        //   (NOT 22:60:00 = 23:00:00 which is what callerNow+duration would give).
        Instant lateSubmit = Instant.parse("2026-06-25T09:42:45Z");

        String paste = ""
                + "Server time: 10:12:45 (UTC +01:00)\n"
                + "Incoming troops (1)\n"
                + "A01 Raphaelo\tmark attackNinja Kornjaca raids Natars -78|150\n"
                + "(-104|54)\tPhalanx\n"
                + "Troops\t0\t1\n"
                + "Arrival\tin 13:17:15 hrs.at 23:30:00\n";

        List<ParsedIncomingAttack> parsed =
                RallyPointIncomingParser.parsePastedContent(paste, lateSubmit);
        assertThat(parsed).hasSize(1);
        // Page rendered at server 10:12:45 UTC+1 = 09:12:45 UTC
        // + duration 13:17:15 = arrival at 22:30:00 UTC = "at 23:30:00 server local"
        assertThat(parsed.get(0).arrivalAt())
                .isEqualTo(Instant.parse("2026-06-25T22:30:00Z"));
    }

    /**
     * Helper-level pin. Standalone so the regression path is obvious in
     * stacktraces: any change to the header regex or the date-shift logic
     * trips this directly.
     */
    @Test
    void resolveServerNowHandlesPositiveTimezone() {
        Instant caller = Instant.parse("2026-06-25T09:42:45Z");
        Instant resolved = RallyPointIncomingParser.resolveServerNow(
                "Server time: 10:12:45 (UTC +01:00)", caller);
        // 10:12:45 in UTC+1 = 09:12:45 UTC on the same date the caller sees.
        assertThat(resolved).isEqualTo(Instant.parse("2026-06-25T09:12:45Z"));
    }

    /**
     * Missing or unrecognised header → fall back to caller's now. Keeps
     * behaviour unchanged for short scraper-driven imports that don't
     * include the full page chrome.
     */
    @Test
    void resolveServerNowFallsBackWhenHeaderAbsent() {
        Instant caller = Instant.parse("2026-06-25T09:42:45Z");
        Instant resolved = RallyPointIncomingParser.resolveServerNow(
                "Some unrelated text — no header here.", caller);
        assertThat(resolved).isEqualTo(caller);
    }

    @Test
    void ignoresPlainCoordWithoutNearbyDuration() {
        // A bare coordinate from the village-list area on the rally-point
        // page has no H:MM:SS duration within 200 chars — must NOT become a
        // spurious attack row.
        String paste = "S00 Splinter\n(" + UMINUS + "37|21)\nS03 Hun\n("
                + UMINUS + "36|21)\n";
        List<ParsedIncomingAttack> parsed =
                RallyPointIncomingParser.parsePastedContent(paste, NOW);
        assertThat(parsed).isEmpty();
    }
}
