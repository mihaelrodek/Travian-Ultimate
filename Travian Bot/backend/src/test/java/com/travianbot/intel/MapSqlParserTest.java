package com.travianbot.intel;

import com.travianbot.intel.scraper.MapSqlParser;
import com.travianbot.intel.scraper.MapSqlParser.ParsedMapRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit5 — no Spring context. Covers the three behaviours the parser has
 * to get right:
 * <ol>
 *   <li>A normal, fully-populated row decodes all 14 columns.</li>
 *   <li>A nature row ({@code tribeId=4}) is dropped silently.</li>
 *   <li>An escaped single quote inside a name decodes back to a literal apostrophe.</li>
 * </ol>
 */
class MapSqlParserTest {

    private static final String FIXTURE = """
            -- this comment should be skipped
            /* block comment
               with multiple lines */
            INSERT INTO `x_world` VALUES (123456,-50,12,1,4711,'Capital','9988','Ridder','22','Vikings',342,'EU','1','0');
            INSERT INTO `x_world` VALUES (123457,-49,12,4,0,'Oasis','0','Nature','0','',0,'EU','0','0');
            INSERT INTO `x_world` VALUES (123458,10,10,2,4712,'Home','9989','O\\'Brien','0','',88);
            """;

    @Test
    void parsesValidRowSkipsNatureAndUnquotes() {
        List<ParsedMapRow> rows = MapSqlParser.parseStatic(FIXTURE);

        // Two rows expected — the nature row (tribeId=4 and uid=0) is dropped.
        assertThat(rows).hasSize(2);

        ParsedMapRow ridder = rows.get(0);
        assertThat(ridder.mapId()).isEqualTo(123456);
        assertThat(ridder.x()).isEqualTo(-50);
        assertThat(ridder.y()).isEqualTo(12);
        assertThat(ridder.tribeId()).isEqualTo(1);
        assertThat(ridder.vid()).isEqualTo(4711L);
        assertThat(ridder.villageName()).isEqualTo("Capital");
        assertThat(ridder.uid()).isEqualTo(9988L);
        assertThat(ridder.playerName()).isEqualTo("Ridder");
        assertThat(ridder.aid()).isEqualTo(22L);
        assertThat(ridder.allianceName()).isEqualTo("Vikings");
        assertThat(ridder.population()).isEqualTo(342);
        assertThat(ridder.region()).isEqualTo("EU");
        assertThat(ridder.capital()).isTrue();
        assertThat(ridder.harbour()).isFalse();

        // Escaped quote and missing trailing columns (11 fields only).
        ParsedMapRow obrien = rows.get(1);
        assertThat(obrien.playerName()).isEqualTo("O'Brien");
        assertThat(obrien.tribeId()).isEqualTo(2);
        assertThat(obrien.population()).isEqualTo(88);
        // Trailing region/capital/harbour absent → null.
        assertThat(obrien.region()).isNull();
        assertThat(obrien.capital()).isNull();
        assertThat(obrien.harbour()).isNull();
        // Empty alliance string normalises to null.
        assertThat(obrien.allianceName()).isNull();
        assertThat(obrien.aid()).isNull();
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(MapSqlParser.parseStatic("")).isEmpty();
        assertThat(MapSqlParser.parseStatic(null)).isEmpty();
    }
}
