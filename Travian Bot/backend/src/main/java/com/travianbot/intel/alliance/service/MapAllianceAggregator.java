package com.travianbot.intel.alliance.service;

import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.persistence.MapPlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives {@code map_alliance} rows from the current {@code map_player}
 * snapshot for a single server.
 * <p>
 * Called from the {@code InactiveSearchService.syncForServer} pipeline after
 * the player/village upsert pass finishes. The aggregator only looks at
 * {@code MapPlayer} rows — the {@code map.sql} parser writes the player's
 * alliance attribution onto each row, and grouping over those gives us the
 * authoritative current membership without an extra HTTP request.
 * <p>
 * <strong>Empty-alliance handling.</strong> When an alliance previously had
 * members and now has zero, we set its {@code memberCount} to 0 instead of
 * deleting the row — that preserves the cached description / bonuses from
 * any past detail scrape. The user can still browse "disbanded" alliances
 * in the history, and a future re-formation under the same {@code aid}
 * cleanly re-uses the existing row.
 */
@Service
@Transactional
public class MapAllianceAggregator {

    private static final Logger log = LoggerFactory.getLogger(MapAllianceAggregator.class);

    private final MapAllianceRepository allianceRepository;
    private final MapPlayerRepository playerRepository;

    public MapAllianceAggregator(MapAllianceRepository allianceRepository,
                                 MapPlayerRepository playerRepository) {
        this.allianceRepository = allianceRepository;
        this.playerRepository = playerRepository;
    }

    /**
     * Rebuild the alliance aggregates for {@code serverId} from the current
     * set of {@link MapPlayer} rows. Returns the number of MapAlliance rows
     * touched (upserts + zeroed-out disbanded rows).
     */
    public int aggregateForServer(Long serverId, Instant now) {
        if (serverId == null) {
            throw new IllegalArgumentException("serverId is required");
        }
        // We don't have a "findAllByServerId" on the player repo, so use the
        // spec-executor flavour with a tiny spec — cheap, no extra repo method.
        List<MapPlayer> players = playerRepository.findAll((root, q, cb) ->
                cb.equal(root.get("serverId"), serverId));

        // Group by aid only (alliance name is just a label for display; aid is
        // the stable identity). Drop players without an alliance up front.
        Map<Long, AllianceAggregate> groups = new HashMap<>();
        for (MapPlayer p : players) {
            Long aid = p.getTravianAid();
            if (aid == null || aid == 0L) continue;
            AllianceAggregate agg = groups.computeIfAbsent(aid, k -> new AllianceAggregate(aid, p.getAllianceName()));
            agg.memberCount++;
            agg.totalPopulation += (p.getCurrentPopulation() == null ? 0 : p.getCurrentPopulation());
            // First non-null name wins — every member should carry the same name,
            // but defensively avoid clobbering a real name with null.
            if (agg.name == null && p.getAllianceName() != null) {
                agg.name = p.getAllianceName();
            }
        }

        int touched = 0;

        // Upsert each currently-active alliance.
        Set<Long> activeAids = new HashSet<>();
        for (AllianceAggregate agg : groups.values()) {
            activeAids.add(agg.travianAid);
            final Long aid = agg.travianAid;
            final String name = (agg.name == null || agg.name.isBlank())
                    ? ("Alliance #" + aid) // defensive label when map.sql had a blank
                    : agg.name;
            final int members = agg.memberCount;
            final long totalPop = agg.totalPopulation;
            final int avgPop = members > 0 ? (int) Math.round((double) totalPop / members) : 0;
            final Long sid = serverId;
            final Instant capturedAt = now;

            MapAlliance alliance = allianceRepository
                    .findFirstByServerIdAndTravianAid(sid, aid)
                    .orElseGet(() -> allianceRepository.save(new MapAlliance(
                            sid, aid, name, members, totalPop, avgPop, capturedAt, capturedAt)));

            alliance.setName(name);
            alliance.recordSnapshot(members, totalPop, capturedAt);
            allianceRepository.save(alliance);
            touched++;
        }

        // Zero out previously-tracked alliances that have no current members.
        // We keep description/bonuses/lastDetailScrapedAt as-is — that's the
        // historical record the user can still browse.
        List<MapAlliance> tracked = allianceRepository.findAllByServerIdOrderByMemberCountDesc(serverId);
        for (MapAlliance prior : tracked) {
            if (activeAids.contains(prior.getTravianAid())) continue;
            if (prior.getMemberCount() == null || prior.getMemberCount() == 0) continue;
            prior.setMemberCount(0);
            prior.setTotalPopulation(0L);
            prior.setAveragePopulation(0);
            // Do NOT bump lastSeenAt — leave it pinned to the last time the
            // alliance actually had members so the UI can show "disbanded N
            // days ago".
            allianceRepository.save(prior);
            touched++;
        }

        log.debug("Alliance aggregation for server {}: {} active alliances, {} rows touched",
                serverId, groups.size(), touched);
        return touched;
    }

    /** Mutable per-group bucket used during the single-pass aggregation. */
    private static final class AllianceAggregate {
        final Long travianAid;
        String name;
        int memberCount;
        long totalPopulation;

        AllianceAggregate(Long travianAid, String name) {
            this.travianAid = travianAid;
            this.name = name;
            this.memberCount = 0;
            this.totalPopulation = 0L;
        }
    }
}
