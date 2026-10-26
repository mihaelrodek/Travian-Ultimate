package com.travianbot.intel;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the cropper-finder REST surface. We don't try to seed
 * {@code map.sql} data here — the parser is covered separately. This just
 * confirms the routes wire up, validation behaves, and an empty data path
 * returns a clean response.
 */
@AutoConfigureMockMvc
class CropperControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    ServerRepository serverRepository;

    @Autowired
    VillageRepository villageRepository;

    @Test
    void emptyCropperSearchReturnsEmptyArray() throws Exception {
        // The cropper finder needs both a Server row (search scope) AND a
        // pivot Village whose owning account sits on the same canonical url.
        Server server = serverRepository.save(new Server(
                "https://ts1.travian.com",
                "Cropper test server"
        ));
        Account account = accountRepository.save(new Account(
                "https://ts1.travian.com",
                "cropper-tester",
                "secret",
                "CropperMain"
        ));
        Village pivot = villageRepository.save(new Village(account, "Pivot", 0, 0));

        mvc.perform(get("/api/servers/" + server.getId() + "/intel/croppers")
                        .param("nearVillageId", String.valueOf(pivot.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reclassifyOnEmptyServerReturnsZero() throws Exception {
        Server server = serverRepository.save(new Server(
                "https://ts2.travian.com",
                "Cropper test server 2"
        ));

        mvc.perform(post("/api/servers/" + server.getId() + "/intel/croppers/reclassify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classified").value(0));
    }
}
