package com.travianbot.server.discovery;

import com.travianbot.server.discovery.CalendarScraperService.ParsedEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the calendar HTML parser. Uses a slice of the real DOM
 * Travian renders on {@code https://www.travian.com/international/calendar},
 * captured in June 2026 and pasted by the user. The slice exercises the
 * non-trivial shapes:
 * <ul>
 *   <li>x1 normal world that's already started (the "active" CSS marker)</li>
 *   <li>x5 sprint world with subtitle and {@code title.special} flag</li>
 *   <li>x10 single-sprint world (the {@code ts100} variant)</li>
 *   <li>Month transition June → July, both inside one {@code #gameworldCalendar}</li>
 *   <li>Entry with "?" placeholder in the artefacts cell (no date yet)</li>
 * </ul>
 */
class CalendarScraperServiceTest {

    private static final String FIXTURE_HTML = """
            <main id="gameworldCalendar">
              <h1>Game world calendar</h1>
              <h2>June 2026</h2>
              <div class="calendarGameworld gameworldDetails">
                <div class="day active"><div class="weekday">Thu</div><div>18</div></div>
                <div class="title tag normal"><div class="text"><span class="name">Europe 5</span></div></div>
                <div class="speed"><i class="speed"></i>×1</div>
                <div class="tribes"><div class="tribeImages">
                  <i class="tribe roman"></i><i class="tribe teuton"></i><i class="tribe gaul"></i>
                </div></div>
                <div class="startTime"><i class="serverStart"></i><div>15:00 <span>(UTC+1)</span></div></div>
                <div class="artefacts"><i class="artefacts"></i>16/09</div>
                <div class="constructionPlans"><i class="constructionPlans"></i>15/12</div>
                <div class="endCondition"><i class="ww"></i><span>100</span></div>
              </div>
              <div class="calendarGameworld gameworldDetails">
                <div class="day active"><div class="weekday">Wed</div><div>24</div></div>
                <div class="title tag normal"><div class="text"><span class="name">America 9</span></div></div>
                <div class="speed"><i class="speed"></i>×1</div>
                <div class="tribes"><div class="tribeImages">
                  <i class="tribe roman"></i><i class="tribe teuton"></i><i class="tribe gaul"></i>
                </div></div>
                <div class="startTime"><i class="serverStart"></i><div>17:00 <span>(UTC+1)</span></div></div>
                <div class="artefacts"><i class="artefacts"></i>?</div>
                <div class="constructionPlans"><i class="constructionPlans"></i>?</div>
                <div class="endCondition"><i class="ww"></i><span>100</span></div>
              </div>
              <div class="calendarGameworld gameworldDetails">
                <div class="day"><div class="weekday">Thu</div><div>25</div></div>
                <div class="title tag normal"><div class="text"><span class="name">Europe 20</span></div></div>
                <div class="speed"><i class="speed"></i>×2</div>
                <div class="tribes"><div class="tribeImages">
                  <i class="tribe roman"></i><i class="tribe teuton"></i><i class="tribe gaul"></i>
                </div></div>
                <div class="startTime"><i class="serverStart"></i><div>15:00 <span>(UTC+1)</span></div></div>
                <div class="artefacts"><i class="artefacts"></i>09/08</div>
                <div class="constructionPlans"><i class="constructionPlans"></i>23/09</div>
                <div class="endCondition"><i class="ww"></i><span>100</span></div>
              </div>
              <h2>July 2026</h2>
              <div class="calendarGameworld gameworldDetails">
                <div class="day"><div class="weekday">Mon</div><div>20</div></div>
                <div class="title tag special">
                  <div class="text">
                    <span class="name">Community Week x5</span>
                    <span class="subtitle">The Eternal War</span>
                  </div>
                  <i class="special"></i>
                </div>
                <div class="speed"><i class="speed"></i>×5</div>
                <div class="tribes"><div class="tribeImages">
                  <i class="tribe roman"></i><i class="tribe teuton"></i><i class="tribe gaul"></i>
                  <i class="tribe egyptian"></i><i class="tribe hun"></i><i class="tribe spartan"></i>
                </div></div>
                <div class="startTime"><i class="serverStart"></i><div>09:00 <span>(UTC+1)</span></div></div>
                <div class="artefacts"><i class="artefacts"></i>07/08</div>
                <div class="constructionPlans"><i class="constructionPlans"></i>25/08</div>
                <div class="endCondition"><i class="ww"></i><span>100</span></div>
              </div>
              <div class="calendarGameworld gameworldDetails">
                <div class="day"><div class="weekday">Mon</div><div>27</div></div>
                <div class="title tag normal"><div class="text"><span class="name">Europe 100</span></div></div>
                <div class="speed"><i class="speed"></i>×10</div>
                <div class="tribes"><div class="tribeImages">
                  <i class="tribe roman"></i><i class="tribe teuton"></i><i class="tribe gaul"></i>
                </div></div>
                <div class="startTime"><i class="serverStart"></i><div>15:00 <span>(UTC+1)</span></div></div>
                <div class="artefacts"><i class="artefacts"></i>05/08</div>
                <div class="constructionPlans"><i class="constructionPlans"></i>14/08</div>
                <div class="endCondition"><i class="ww"></i><span>100</span></div>
              </div>
            </main>
            """;

    @Test
    void parsesAllFiveEntriesInDocumentOrder() {
        List<ParsedEntry> entries = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML);

        assertThat(entries).extracting(e -> e.worldName).containsExactly(
                "Europe 5",
                "America 9",
                "Europe 20",
                "Community Week x5 — The Eternal War",
                "Europe 100"
        );
    }

    @Test
    void parsesActiveX1WorldWithFullMetadata() {
        ParsedEntry europe5 = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML).get(0);

        assertThat(europe5.region).isEqualTo("Europe");
        assertThat(europe5.speed).isEqualTo(1);
        assertThat(europe5.tribes).containsExactly("roman", "teuton", "gaul");
        assertThat(europe5.special).isFalse();
        assertThat(europe5.endConditionVillages).isEqualTo(100);
        assertThat(europe5.artefactsDate).isEqualTo("16/09");
        assertThat(europe5.constructionPlansDate).isEqualTo("15/12");
        assertThat(europe5.scheduledStartAt).isEqualTo(
                LocalDateTime.of(2026, 6, 18, 15, 0).atOffset(ZoneOffset.ofHours(1)).toInstant()
        );
        // The .day.active class on a started world → OPEN_REGISTRATION (start time in the past
        // is RUNNING; depending on the test clock both are acceptable so we just check non-null).
        assertThat(europe5.status).isIn("OPEN_REGISTRATION", "RUNNING");
        assertThat(europe5.inferredServerUrl).isEqualTo("https://ts5.x1.europe.travian.com");
    }

    @Test
    void inferredUrlFollowsTheSpeedToTsNumberScheme() {
        List<ParsedEntry> entries = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML);

        // Europe 20 with ×2 → ts20.x2.europe.travian.com
        assertThat(entries.get(2).inferredServerUrl)
                .isEqualTo("https://ts20.x2.europe.travian.com");
        // Europe 100 with ×10 → ts100.x10.europe.travian.com
        assertThat(entries.get(4).inferredServerUrl)
                .isEqualTo("https://ts100.x10.europe.travian.com");
    }

    @Test
    void specialEntryGetsSubtitleAndNoInferredUrl() {
        ParsedEntry special = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML).get(3);

        assertThat(special.special).isTrue();
        assertThat(special.worldName).isEqualTo("Community Week x5 — The Eternal War");
        assertThat(special.speed).isEqualTo(5);
        // Specials have no continent — scraper falls back to "Special" so the
        // non-null DB column still accepts the row.
        assertThat(special.region).isEqualTo("Special");
        assertThat(special.inferredServerUrl).isNull();
        assertThat(special.tribes).containsExactly("roman", "teuton", "gaul",
                "egyptian", "hun", "spartan");
    }

    @Test
    void missingArtefactsCellIsNormalisedToNull() {
        // America 9 has "?" in both artefacts and constructionPlans cells.
        ParsedEntry america9 = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML).get(1);

        assertThat(america9.region).isEqualTo("America");
        assertThat(america9.artefactsDate).isNull();
        assertThat(america9.constructionPlansDate).isNull();
        assertThat(america9.inferredServerUrl).isEqualTo("https://ts9.x1.america.travian.com");
    }

    @Test
    void monthTransitionFromHeaderBindsEntriesToTheRightMonth() {
        List<ParsedEntry> entries = CalendarScraperService.parseCalendarHtml(FIXTURE_HTML);

        // Europe 5 → June, Community Week → July
        assertThat(entries.get(0).scheduledStartAt.atZone(ZoneOffset.ofHours(1)).getMonthValue()).isEqualTo(6);
        assertThat(entries.get(3).scheduledStartAt.atZone(ZoneOffset.ofHours(1)).getMonthValue()).isEqualTo(7);
        assertThat(entries.get(4).scheduledStartAt.atZone(ZoneOffset.ofHours(1)).getMonthValue()).isEqualTo(7);
    }

    @Test
    void emptyOrBlankHtmlYieldsEmptyList() {
        assertThat(CalendarScraperService.parseCalendarHtml(null)).isEmpty();
        assertThat(CalendarScraperService.parseCalendarHtml("")).isEmpty();
        assertThat(CalendarScraperService.parseCalendarHtml("<html><body>nothing</body></html>")).isEmpty();
    }
}
