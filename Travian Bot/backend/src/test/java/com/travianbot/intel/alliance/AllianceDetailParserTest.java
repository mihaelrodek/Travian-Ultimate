package com.travianbot.intel.alliance;

import com.travianbot.intel.alliance.scraper.AllianceDetailParser;
import com.travianbot.intel.alliance.scraper.AllianceDetailParser.ParsedAllianceDetail;
import com.travianbot.intel.alliance.scraper.AllianceDetailParser.ParsedAllianceMember;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit5 (no Spring) coverage for the alliance detail HTML parser.
 * Covers three behaviours the parser has to be right on:
 * <ol>
 *   <li>A full page with description + roster + bonuses decodes cleanly.</li>
 *   <li>The roster supports both legacy {@code ?uid=N} and modern
 *       {@code /profile/N} link patterns side-by-side.</li>
 *   <li>An empty / nonsense input produces a best-effort empty result
 *       instead of throwing.</li>
 * </ol>
 */
class AllianceDetailParserTest {

    private static final String FIXTURE = """
            <html><body>
              <h1>Alliance Vikings</h1>
              <div class="description">
                Casual EU sim alliance — drink tea, raid Natars, no drama.
              </div>

              <table class="members">
                <thead>
                  <tr><th>Player</th><th>Population</th><th>Rank</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td><a href="/profile/123">Ridder</a></td>
                    <td>1,250</td>
                    <td>Leader</td>
                  </tr>
                  <tr>
                    <td><a href="spieler.php?uid=456">O'Brien</a></td>
                    <td>980</td>
                    <td>Member</td>
                  </tr>
                </tbody>
              </table>

              <div class="bonuses">
                <table>
                  <tr><td>Diplomacy</td><td>Level 3</td></tr>
                  <tr><td>Mining</td><td>Level 5</td></tr>
                  <tr><td>Scout</td><td>Level 2</td></tr>
                  <tr><td>Brewery</td><td>Level 1</td></tr>
                </table>
              </div>
            </body></html>
            """;

    @Test
    void parsesFullAlliancePage() {
        ParsedAllianceDetail parsed = AllianceDetailParser.parse(FIXTURE);

        // Description trimmed but preserved verbatim.
        assertThat(parsed.description())
                .contains("Casual EU sim alliance")
                .contains("no drama");

        // Roster: two members, correct uids from each link style.
        List<ParsedAllianceMember> members = parsed.members();
        assertThat(members).hasSize(2);

        ParsedAllianceMember ridder = members.get(0);
        assertThat(ridder.uid()).isEqualTo(123L);
        assertThat(ridder.name()).isEqualTo("Ridder");
        assertThat(ridder.population()).isEqualTo(1250);
        assertThat(ridder.role()).isEqualTo("Leader");

        ParsedAllianceMember obrien = members.get(1);
        assertThat(obrien.uid()).isEqualTo(456L);
        assertThat(obrien.name()).isEqualTo("O'Brien");
        assertThat(obrien.population()).isEqualTo(980);
        assertThat(obrien.role()).isEqualTo("Member");

        // Bonuses — all four standard ones recognised.
        assertThat(parsed.bonusDiplomacy()).isEqualTo(3);
        assertThat(parsed.bonusMining()).isEqualTo(5);
        assertThat(parsed.bonusScout()).isEqualTo(2);
        assertThat(parsed.bonusBrewery()).isEqualTo(1);

        // Raw JSON keeps every bonus level we saw.
        assertThat(parsed.rawBonusesJson())
                .contains("\"diplomacy\":3")
                .contains("\"mining\":5")
                .contains("\"scout\":2")
                .contains("\"brewery\":1");
    }

    @Test
    void nullAndEmptyInputReturnEmptyResult() {
        ParsedAllianceDetail empty = AllianceDetailParser.parse("");
        assertThat(empty.description()).isEmpty();
        assertThat(empty.members()).isEmpty();
        assertThat(empty.bonusDiplomacy()).isNull();
        assertThat(empty.rawBonusesJson()).isEqualTo("{}");

        ParsedAllianceDetail nul = AllianceDetailParser.parse(null);
        assertThat(nul.members()).isEmpty();
        assertThat(nul.rawBonusesJson()).isEqualTo("{}");
    }

    @Test
    void missingPiecesDegradeGracefully() {
        // A page with a roster but no description and no bonuses table — the
        // parser shouldn't throw and shouldn't fabricate bonus values.
        String partial = """
                <html><body>
                  <table>
                    <tr><th>Name</th><th>Population</th></tr>
                    <tr><td><a href="/profile/77">Solo</a></td><td>500</td></tr>
                  </table>
                </body></html>
                """;
        ParsedAllianceDetail parsed = AllianceDetailParser.parse(partial);
        assertThat(parsed.description()).isEmpty();
        assertThat(parsed.members()).hasSize(1);
        assertThat(parsed.members().get(0).uid()).isEqualTo(77L);
        assertThat(parsed.bonusDiplomacy()).isNull();
        assertThat(parsed.bonusMining()).isNull();
    }
}
