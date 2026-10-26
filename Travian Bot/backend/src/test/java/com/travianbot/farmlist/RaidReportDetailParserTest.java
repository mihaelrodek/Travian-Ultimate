package com.travianbot.farmlist;

import com.travianbot.farmlist.scraper.RaidReportDetailParser;
import com.travianbot.farmlist.scraper.RaidReportDetailParser.ParsedReportDetail;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit5 — no Spring context. Exercises the detail parser against a
 * synthetic fixture for a perfect raid (no losses), which is the most
 * common case in production and the one the analyzer relies on for the
 * GREEN traffic-light path.
 */
class RaidReportDetailParserTest {

    /**
     * Minimal fixture that mirrors the shape of a real T4.6 victory-no-loss
     * report:
     * <ul>
     *   <li>iReport0 icon (perfect raid)</li>
     *   <li>resource panel with {@code r1..r4} markers + numbers</li>
     *   <li>carry footer "4000 / 4000" (full haul)</li>
     *   <li>attacker troop table with identical sent/returned rows</li>
     *   <li>karte link with target coords</li>
     * </ul>
     */
    private static final String VICTORY_NO_LOSS = """
            <!DOCTYPE html>
            <html><body>
              <h3>Raid on
                <a href="karte.php?x=-12&y=7">Nature village</a>
              </h3>
              <img class="iReport0" alt="perfect raid"/>
              <table class="troopInfo">
                <tr><td>Phalanx</td><td>Legio</td><td>Praetorian</td></tr>
                <tr><td>50</td><td>20</td><td>5</td></tr>
                <tr><td>50</td><td>20</td><td>5</td></tr>
              </table>
              <div class="bounty">
                <span class="resource"><i class="r1"></i> 1200</span>
                <span class="resource"><i class="r2"></i> 1100</span>
                <span class="resource"><i class="r3"></i> 900</span>
                <span class="resource"><i class="r4"></i> 800</span>
                <span class="carry">4000 / 4000</span>
              </div>
            </body></html>
            """;

    @Test
    void parsesVictoryNoLossEndToEnd() {
        ParsedReportDetail d = RaidReportDetailParser.parse(VICTORY_NO_LOSS);

        // Outcome bucket follows the iReport0 icon.
        assertThat(d.outcome()).isEqualTo("VICTORY_NO_LOSS");

        // Loot quartet — exact integers out of the r1..r4 spans.
        assertThat(d.woodLooted()).isEqualTo(1200);
        assertThat(d.clayLooted()).isEqualTo(1100);
        assertThat(d.ironLooted()).isEqualTo(900);
        assertThat(d.cropLooted()).isEqualTo(800);

        // Carry footer parses, and "4000 / 4000" is a full haul.
        assertThat(d.carryCapacityTotal()).isEqualTo(4000);
        assertThat(d.wasFullHaul()).isTrue();

        // Sent == returned for every column → zero losses.
        assertThat(d.ourTroopsLost()).isZero();

        // Coords pulled from the karte link in the headline.
        assertThat(d.targetX()).isEqualTo(-12);
        assertThat(d.targetY()).isEqualTo(7);
    }

    @Test
    void handlesEmptyHtmlGracefully() {
        // Parser is documented as never throwing — empty in, empty-defaults out.
        ParsedReportDetail d = RaidReportDetailParser.parse("");
        assertThat(d.outcome()).isEqualTo("UNKNOWN");
        assertThat(d.woodLooted()).isZero();
        assertThat(d.ourTroopsLost()).isZero();
    }
}
