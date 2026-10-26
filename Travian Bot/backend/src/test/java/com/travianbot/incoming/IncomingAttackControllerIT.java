package com.travianbot.incoming;

import com.travianbot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the incoming-attacks feature. Exercises the empty-DB happy
 * path through the full controller-service-repository stack so a wiring
 * regression (missing bean, malformed route) trips this test on CI.
 */
@AutoConfigureMockMvc
class IncomingAttackControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listIncomingReturnsEmptyListForUnknownAccount() throws Exception {
        mvc.perform(get("/api/accounts/1/incoming"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
