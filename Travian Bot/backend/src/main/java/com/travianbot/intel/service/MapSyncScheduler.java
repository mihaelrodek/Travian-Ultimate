package com.travianbot.intel.service;

import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Daily map.sql sync — runs once at 04:17 server time (offset just enough from
 * the top of the hour that we don't collide with other schedulers that prefer
 * round-minute crons). map.sql is regenerated nightly by Travian, so daily is
 * the right cadence: faster wouldn't surface new data, slower would let the
 * 7d / 30d snapshots drift.
 * <p>
 * Iterates the active {@link Server} rows (not accounts) — the inactive-search
 * feature is server-scoped now, so one sync per tracked server is enough no
 * matter how many local accounts live on each. Soft-disabled servers are
 * skipped via the {@code findAllByActiveTrueOrderByNameAsc} query so users can
 * "park" a server without removing it.
 */
@Component
public class MapSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MapSyncScheduler.class);

    private final ServerRepository serverRepository;
    private final InactiveSearchService inactiveSearchService;

    public MapSyncScheduler(ServerRepository serverRepository,
                            InactiveSearchService inactiveSearchService) {
        this.serverRepository = serverRepository;
        this.inactiveSearchService = inactiveSearchService;
    }

    /** Daily at 04:17. */
    @Scheduled(cron = "0 17 4 * * *")
    public void runDaily() {
        List<Server> servers = serverRepository.findAllByActiveTrueOrderByNameAsc();
        if (servers.isEmpty()) {
            log.debug("MapSyncScheduler: no active servers, nothing to sync");
            return;
        }
        log.info("MapSyncScheduler: kicking off map.sql sync for {} server(s)", servers.size());
        for (Server server : servers) {
            try {
                int upserts = inactiveSearchService.syncForServer(server.getId());
                log.info("map.sql sync done for server {} ({}) — {} players upserted",
                        server.getId(), server.getUrl(), upserts);
            } catch (Exception e) {
                // WARN not ERROR — one bad server (DNS, maintenance) shouldn't make
                // the daily run feel "broken". The bot-event log line carries the
                // detail for the user-facing surface.
                log.warn("map.sql sync failed for server {} ({}): {}",
                        server.getId(), server.getUrl(), e.getMessage());
            }
        }
    }
}
