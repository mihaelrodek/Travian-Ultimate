package com.travianbot.intel.alliance;

import com.travianbot.AbstractIntegrationTest;
import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.intel.alliance.service.MapAllianceAggregator;
import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the aggregator groups {@link MapPlayer} rows by alliance correctly
 * and zeros out previously-tracked alliances that no longer have members.
 * <p>
 * Uses {@code @SpringBootTest} (via {@link AbstractIntegrationTest}) because
 * the aggregator hits two real repositories — mocking JPA spec executors
 * here would be more friction than value.
 */
class MapAllianceAggregatorTest extends AbstractIntegrationTest {

    @Autowired
    MapAllianceAggregator aggregator;

    @Autowired
    MapPlayerRepository playerRepository;

    @Autowired
    MapAllianceRepository allianceRepository;

    @Autowired
    ServerRepository serverRepository;

    @Test
    void aggregatesPlayersByAllianceAndZerosDisbanded() {
        Server server = serverRepository.save(new Server(
                "https://ts-aggregator.travian.com",
                "Aggregator test server"
        ));
        Long sid = server.getId();

        // Alliance A: 2 members (pop 100 + 300 → avg 200)
        MapPlayer a1 = new MapPlayer(sid, 1001L, "Alice", 1, 100, 1);
        a1.setAlliance(10L, "Alpha");
        playerRepository.save(a1);
        MapPlayer a2 = new MapPlayer(sid, 1002L, "Bob", 1, 300, 2);
        a2.setAlliance(10L, "Alpha");
        playerRepository.save(a2);

        // Alliance B: 1 member (pop 500)
        MapPlayer b1 = new MapPlayer(sid, 1003L, "Charlie", 2, 500, 3);
        b1.setAlliance(20L, "Bravo");
        playerRepository.save(b1);

        // No-alliance player — must NOT show up anywhere.
        playerRepository.save(new MapPlayer(sid, 1004L, "Loner", 3, 50, 1));

        // Pre-existing disbanded alliance row that no current player belongs to —
        // should be zeroed, not deleted.
        MapAlliance ghost = allianceRepository.save(new MapAlliance(
                sid, 99L, "Ghost", 5, 1234L, 246, Instant.now().minusSeconds(86400), Instant.now().minusSeconds(86400)
        ));
        ghost.setDescription("survives disband");
        allianceRepository.save(ghost);

        // Act
        Instant now = Instant.now();
        int touched = aggregator.aggregateForServer(sid, now);

        // Assert — 2 active alliances upserted + 1 disbanded zeroed.
        assertThat(touched).isEqualTo(3);

        Optional<MapAlliance> alpha = allianceRepository.findFirstByServerIdAndTravianAid(sid, 10L);
        assertThat(alpha).isPresent();
        assertThat(alpha.get().getName()).isEqualTo("Alpha");
        assertThat(alpha.get().getMemberCount()).isEqualTo(2);
        assertThat(alpha.get().getTotalPopulation()).isEqualTo(400L);
        assertThat(alpha.get().getAveragePopulation()).isEqualTo(200);

        Optional<MapAlliance> bravo = allianceRepository.findFirstByServerIdAndTravianAid(sid, 20L);
        assertThat(bravo).isPresent();
        assertThat(bravo.get().getMemberCount()).isEqualTo(1);
        assertThat(bravo.get().getTotalPopulation()).isEqualTo(500L);
        assertThat(bravo.get().getAveragePopulation()).isEqualTo(500);

        // Ghost survives but is zeroed; the description is preserved.
        Optional<MapAlliance> stillThere = allianceRepository.findFirstByServerIdAndTravianAid(sid, 99L);
        assertThat(stillThere).isPresent();
        assertThat(stillThere.get().getMemberCount()).isEqualTo(0);
        assertThat(stillThere.get().getTotalPopulation()).isEqualTo(0L);
        assertThat(stillThere.get().getDescription()).isEqualTo("survives disband");

        // The no-alliance Loner did not create a stray row.
        List<MapAlliance> all = allianceRepository.findAllByServerIdOrderByMemberCountDesc(sid);
        assertThat(all).extracting(MapAlliance::getTravianAid)
                .containsExactlyInAnyOrder(10L, 20L, 99L);
    }
}
