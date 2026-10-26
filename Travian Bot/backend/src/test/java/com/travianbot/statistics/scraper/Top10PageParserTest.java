package com.travianbot.statistics.scraper;

import com.travianbot.statistics.domain.StatCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-fixture regression coverage for {@link Top10PageParser} (roadmap
 * TEST1). The fixture {@code /fixtures/statistics/top10-page.html} is a real
 * capture of a T4.6 Legends {@code /statistics/player/top10} page (the same
 * bytes the scraper dumped to {@code build/debug} in production), so this test
 * pins the parser against markup it actually has to handle — duplicate
 * {@code #top10_offs} ids, U+202D/U+202C directional marks and comma thousands
 * separators around the numbers, and the trailing {@code tr.own.hl} "your
 * rank" row that must be excluded.
 * <p>
 * The synthetic cases below lock the trickier structural rules (positional
 * PvP/PvE split on the duplicate id, own-row exclusion, empty input) in
 * isolation so a failure points straight at the rule that broke.
 */
class Top10PageParserTest {

    private static final String GOLDEN = loadFixture("/fixtures/statistics/top10-page.html");

    @Test
    void parsesAllFourCategoriesFromRealCapture() {
        Map<StatCategory, List<ParsedTop10Row>> result = Top10PageParser.parseAll(GOLDEN);

        assertThat(result).containsOnlyKeys(
                StatCategory.PVP, StatCategory.PVE, StatCategory.DEFENSE, StatCategory.RAIDERS);
        assertThat(result.values()).allSatisfy(rows -> assertThat(rows).hasSize(10));
    }

    @Test
    void pvpTableDecodesRanksNamesAndScores() {
        List<ParsedTop10Row> pvp = Top10PageParser.parseAll(GOLDEN).get(StatCategory.PVP);

        // Ranks are exactly 1..10 in order — proves the separator/own rows are skipped.
        assertThat(pvp).extracting(ParsedTop10Row::rank)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        assertThat(pvp.get(0)).isEqualTo(new ParsedTop10Row(1, "Mr. Nimbus", 1092L));
        assertThat(pvp.get(9)).isEqualTo(new ParsedTop10Row(10, "La1end", 488L));
    }

    @Test
    void pveIsTheSecondOffsTableNotTheFirst() {
        // PvP and PvE share id=top10_offs; the parser must split them positionally.
        List<ParsedTop10Row> pve = Top10PageParser.parseAll(GOLDEN).get(StatCategory.PVE);

        assertThat(pve.get(0)).isEqualTo(new ParsedTop10Row(1, "Hairy Orange", 19332L));
        assertThat(pve.get(9).playerName()).isEqualTo("Hello Kitty");
    }

    @Test
    void defenseAndRaidersDecodeIncludingLargeCommaSeparatedScores() {
        Map<StatCategory, List<ParsedTop10Row>> result = Top10PageParser.parseAll(GOLDEN);

        assertThat(result.get(StatCategory.DEFENSE).get(0))
                .isEqualTo(new ParsedTop10Row(1, "EDMIRE", 678L));

        // 5,096,306 — exercises directional-mark + thousands-separator stripping
        // on a value well past what a naive Integer.parse would survive.
        assertThat(result.get(StatCategory.RAIDERS).get(0))
                .isEqualTo(new ParsedTop10Row(1, "Hairy Orange", 5_096_306L));
    }

    @Test
    void scoresNeverLeakDirectionalMarksOrSeparators() {
        Top10PageParser.parseAll(GOLDEN).values().forEach(rows ->
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.score()).isNotNull();
                    assertThat(row.playerName()).doesNotContain("‭", "‬");
                }));
    }

    // ---- synthetic structural rules --------------------------------------

    @Test
    void singleOffsTableYieldsPvpOnly() {
        String html = """
                <table class="top10" id="top10_offs"><tbody>
                  <tr class="hover"><td>1.</td><td></td><td><a href="/profile/1">Solo</a></td><td>10</td></tr>
                </tbody></table>
                """;
        Map<StatCategory, List<ParsedTop10Row>> result = Top10PageParser.parseAll(html);

        assertThat(result).containsOnlyKeys(StatCategory.PVP);
        assertThat(result.get(StatCategory.PVP)).containsExactly(new ParsedTop10Row(1, "Solo", 10L));
    }

    @Test
    void ownHighlightRowIsExcluded() {
        String html = """
                <table class="top10" id="top10_defs"><tbody>
                  <tr class="hover"><td>1.</td><td></td><td><a href="/profile/1">Real</a></td><td>50</td></tr>
                  <tr class="own hl"><td>42.</td><td></td><td><a href="/profile/9">Me</a></td><td>3</td></tr>
                </tbody></table>
                """;
        List<ParsedTop10Row> rows = Top10PageParser.parseAll(html).get(StatCategory.DEFENSE);

        assertThat(rows).containsExactly(new ParsedTop10Row(1, "Real", 50L));
    }

    @Test
    void capsAtTenRowsPerTable() {
        StringBuilder sb = new StringBuilder("<table class=\"top10\" id=\"top10_raiders\"><tbody>");
        for (int i = 1; i <= 15; i++) {
            sb.append("<tr class=\"hover\"><td>").append(i)
              .append(".</td><td></td><td><a href=\"/profile/").append(i)
              .append("\">P").append(i).append("</a></td><td>").append(i).append("</td></tr>");
        }
        sb.append("</tbody></table>");

        assertThat(Top10PageParser.parseAll(sb.toString()).get(StatCategory.RAIDERS)).hasSize(10);
    }

    @Test
    void emptyOrTablelessInputYieldsEmptyMapWithoutThrowing() {
        assertThat(Top10PageParser.parseAll("")).isEmpty();
        assertThat(Top10PageParser.parseAll("<html><body>no tables here</body></html>")).isEmpty();
    }

    private static String loadFixture(String path) {
        try (InputStream in = Top10PageParserTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath fixture %s must exist", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture " + path, e);
        }
    }
}
