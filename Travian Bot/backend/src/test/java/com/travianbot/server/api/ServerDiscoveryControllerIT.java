package com.travianbot.server.api;

import com.travianbot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import org.hamcrest.Matchers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the discovery controller.
 * <p>
 * We deliberately DO NOT exercise the real POST endpoint here — that would
 * fire ~600 HTTP probes at travian.com, which is rude and flaky in CI. The
 * {@code /candidates} GET is a pure deterministic projection of the
 * generator constants and is safe to assert on.
 */
@AutoConfigureMockMvc
class ServerDiscoveryControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void candidatesEndpointReturnsNonEmptyList() throws Exception {
        mvc.perform(get("/api/servers/discovery/candidates"))
                .andExpect(status().isOk())
                // List length is a lower-bound assertion — any change to the
                // generator that drops several communities will fail here.
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(200)))
                // Spot-check one well-known URL — change to CandidateUrlGenerator
                // dropping the .com community would be caught by this hasItem.
                .andExpect(jsonPath("$", Matchers.hasItem("https://ts1.x1.com.travian.com")));
    }
}
