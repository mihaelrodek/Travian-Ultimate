package com.travianbot.intel;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-level smoke test for the inactive-search controller. We
 * deliberately exercise only the read endpoints here — the {@code POST /sync}
 * path goes out to the public Travian map.sql which would make the test flaky
 * and slow. The sync path is covered by manual / staging runs.
 */
@AutoConfigureMockMvc
class InactiveSearchControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ServerRepository serverRepository;

    @Test
    void emptyInactivesReturnsEmptyPage() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts1.travian.com",
                "Intel test server"
        ));

        mvc.perform(get("/api/servers/" + server.getId() + "/intel/inactives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void unknownPlayerReturns404() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts2.travian.com",
                "Intel test server 2"
        ));

        mvc.perform(get("/api/servers/" + server.getId() + "/intel/players/99999"))
                .andExpect(status().isNotFound());
    }
}
