package com.travianbot.farmlist;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.domain.RaidReport;
import com.travianbot.farmlist.persistence.FarmlistRepository;
import com.travianbot.farmlist.persistence.RaidReportRepository;
import com.travianbot.farmlist.service.FarmlistAnalyzerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check of the analytics endpoints: persist a farmlist with one
 * target, ingest two raid reports (one clean, one with losses), then hit the
 * controller and assert it surfaces the rolled-up metrics + the empty
 * prune-candidate list.
 */
@AutoConfigureMockMvc
class FarmlistAnalyzerControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepository;
    @Autowired FarmlistRepository farmlistRepository;
    @Autowired RaidReportRepository raidReportRepository;
    @Autowired FarmlistAnalyzerService analyzerService;

    @Test
    void metricsReflectIngestedReports() throws Exception {
        Account account = accountRepository.save(new Account(
                "https://ts-analytics.travian.com",
                "analytics-tester",
                "secret",
                "AnalyticsMain"));

        Farmlist fl = new Farmlist(account, "Test Loop");
        FarmlistTarget target = new FarmlistTarget(fl, "Farm A", -5, 12, "{\"t1\":50}");
        fl.getTargets().add(target);
        Farmlist savedFl = farmlistRepository.save(fl);
        FarmlistTarget savedTarget = savedFl.getTargets().get(0);

        // Report 1: clean victory, large haul (3000+2500+2000+1500 = 9000) —
        // keeps total above the 5000 RED-via-low-loot threshold.
        RaidReport r1 = new RaidReport(
                account.getId(), savedFl.getId(), 1001L,
                -5, 12, Instant.now().minusSeconds(120));
        r1.setFarmlistTargetId(savedTarget.getId());
        r1.setLoot(3000, 2500, 2000, 1500);
        r1.setCarryCapacityTotal(9000);
        r1.setWasFullHaul(false);
        r1.setOutcome("VICTORY_NO_LOSS");
        r1.setOurTroopsLost(0);
        raidReportRepository.save(r1);
        analyzerService.ingest(r1);

        // Report 2: victory with losses — 1500 looted, 2 troops lost.
        RaidReport r2 = new RaidReport(
                account.getId(), savedFl.getId(), 1002L,
                -5, 12, Instant.now().minusSeconds(60));
        r2.setFarmlistTargetId(savedTarget.getId());
        r2.setLoot(500, 400, 400, 200);
        r2.setCarryCapacityTotal(4000);
        r2.setOutcome("VICTORY_WITH_LOSS");
        r2.setOurTroopsLost(2);
        raidReportRepository.save(r2);
        analyzerService.ingest(r2);

        // Hit /metrics — expect a single row with the rolled-up totals.
        // Total loot = 9000 + 1500 = 10500 (above the 5000 RED-via-low-loot
        // threshold) and consecutive losses = 1 (below the 3-loss RED
        // threshold), so the rule chain falls through to the YELLOW branch:
        // raidsWithLosses/totalRaids = 1/2 = 0.5 > 0.25 → YELLOW.
        mvc.perform(get("/api/accounts/" + account.getId()
                        + "/farmlists/" + savedFl.getId() + "/analytics/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].farmlistTargetId").value(savedTarget.getId()))
                .andExpect(jsonPath("$[0].totalRaids").value(2))
                .andExpect(jsonPath("$[0].totalResourcesLooted").value(9000 + 1500))
                .andExpect(jsonPath("$[0].totalOurTroopsLost").value(2))
                .andExpect(jsonPath("$[0].consecutiveLossRaids").value(1))
                .andExpect(jsonPath("$[0].trafficLight").value("YELLOW"))
                .andExpect(jsonPath("$[0].prunCandidate").value(false));

        // Empty prune candidates — no target has hit the 3-consecutive-losses
        // threshold yet.
        mvc.perform(get("/api/accounts/" + account.getId()
                        + "/farmlists/" + savedFl.getId() + "/analytics/prune-candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
