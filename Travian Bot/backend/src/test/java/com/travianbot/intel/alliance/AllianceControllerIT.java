package com.travianbot.intel.alliance;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.domain.MapAllianceMember;
import com.travianbot.intel.alliance.persistence.MapAllianceMemberRepository;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for {@code /api/servers/{id}/intel/alliances/*}. We seed
 * MapAlliance + MapAllianceMember rows directly rather than running a sync —
 * the parser / fetcher / scheduler all have their own tests.
 */
@AutoConfigureMockMvc
class AllianceControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ServerRepository serverRepository;

    @Autowired
    MapAllianceRepository allianceRepository;

    @Autowired
    MapAllianceMemberRepository memberRepository;

    @Test
    void listReturnsPaginatedAlliances() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts-list.travian.com", "Alliance list test"
        ));
        Long sid = server.getId();
        Instant now = Instant.now();
        allianceRepository.save(new MapAlliance(sid, 1L, "Alpha", 5, 1500L, 300, now, now));
        allianceRepository.save(new MapAlliance(sid, 2L, "Bravo", 10, 5000L, 500, now, now));

        mvc.perform(get("/api/servers/" + sid + "/intel/alliances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                // Default sort = memberCount desc → Bravo first.
                .andExpect(jsonPath("$.items[0].name").value("Bravo"))
                .andExpect(jsonPath("$.items[0].memberCount").value(10))
                .andExpect(jsonPath("$.items[1].name").value("Alpha"));
    }

    @Test
    void getByAidReturnsDetailWithMembers() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts-detail.travian.com", "Alliance detail test"
        ));
        Long sid = server.getId();
        Instant now = Instant.now();
        MapAlliance alliance = allianceRepository.save(
                new MapAlliance(sid, 42L, "DetailMe", 3, 900L, 300, now, now)
        );
        alliance.recordDetail("hello world", 2, 4, 1, 0, "{\"diplomacy\":2}", now);
        allianceRepository.save(alliance);

        memberRepository.save(new MapAllianceMember(
                alliance.getId(), 7001L, "Veteran", 600, "Leader", now));
        memberRepository.save(new MapAllianceMember(
                alliance.getId(), 7002L, "Recruit", 300, "Member", now));

        mvc.perform(get("/api/servers/" + sid + "/intel/alliances/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DetailMe"))
                .andExpect(jsonPath("$.travianAid").value(42))
                .andExpect(jsonPath("$.description").value("hello world"))
                .andExpect(jsonPath("$.bonusDiplomacy").value(2))
                .andExpect(jsonPath("$.bonusMining").value(4))
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(2))
                // Members ordered by population descending — Veteran first.
                .andExpect(jsonPath("$.members[0].name").value("Veteran"))
                .andExpect(jsonPath("$.members[0].uid").value(7001))
                .andExpect(jsonPath("$.members[0].role").value("Leader"));
    }

    @Test
    void unknownAidReturns404() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts-404.travian.com", "Alliance 404 test"
        ));
        mvc.perform(get("/api/servers/" + server.getId() + "/intel/alliances/9999"))
                .andExpect(status().isNotFound());
    }
}
