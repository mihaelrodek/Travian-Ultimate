package com.travianbot.server;

import com.travianbot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the {@code /api/servers} REST surface. Just exercises the
 * create + list flow against a real Postgres so the JPA mapping and the
 * canonical-url normalisation both get validated end-to-end.
 */
@AutoConfigureMockMvc
class ServerControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createThenListReturnsTheNewServer() throws Exception {
        // Trailing slash in the input is intentional — verifies the canonical
        // url stripping (the wire response should NOT have a trailing slash).
        String body = "{"
                + "\"url\":\"https://ts5.travian.com/\","
                + "\"name\":\"TS5 Europe\","
                + "\"region\":\"Europe\","
                + "\"gameWorld\":\"com5\""
                + "}";

        mvc.perform(post("/api/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.url").value("https://ts5.travian.com"))
                .andExpect(jsonPath("$.name").value("TS5 Europe"))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(get("/api/servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.url=='https://ts5.travian.com')].name").value("TS5 Europe"));
    }
}
