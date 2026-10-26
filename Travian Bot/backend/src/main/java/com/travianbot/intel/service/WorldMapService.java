package com.travianbot.intel.service;

import com.travianbot.common.error.NotFoundException;
import com.travianbot.intel.api.dto.WorldMapResponse;
import com.travianbot.intel.api.dto.WorldMapVillageResponse;
import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side service for the interactive world map (roadmap MAP1). Assembles the
 * "every village with its owner's attributes" view the canvas renders.
 * <p>
 * The join is done in-memory rather than in SQL: the two tables are already
 * loaded whole for a full-map render, and hydrating {@code MapPlayer} into a
 * {@code uid → player} map once is cheaper (and simpler) than a per-row join.
 * This mirrors how {@code InactiveSearchService.syncForServer} pairs the tables.
 */
@Service
public class WorldMapService {

    private final MapVillageRepository villageRepository;
    private final MapPlayerRepository playerRepository;
    private final ServerRepository serverRepository;

    public WorldMapService(MapVillageRepository villageRepository,
                           MapPlayerRepository playerRepository,
                           ServerRepository serverRepository) {
        this.villageRepository = villageRepository;
        this.playerRepository = playerRepository;
        this.serverRepository = serverRepository;
    }

    /**
     * Build the full-server map view. When a bounding box is supplied (all four
     * bounds non-null) only villages inside it are returned — the canvas passes
     * one when the user is zoomed in on a busy world; a null box returns every
     * village.
     */
    @Transactional(readOnly = true)
    public WorldMapResponse mapForServer(Long serverId, Integer xMin, Integer xMax, Integer yMin, Integer yMax) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new NotFoundException("Server " + serverId + " not found"));

        List<MapVillage> villages = hasBox(xMin, xMax, yMin, yMax)
                ? villageRepository.findAllByServerIdAndXBetweenAndYBetween(serverId, xMin, xMax, yMin, yMax)
                : villageRepository.findAllByServerId(serverId);

        Map<Long, MapPlayer> playersByUid = new HashMap<>();
        for (MapPlayer p : playerRepository.findAllByServerId(serverId)) {
            playersByUid.put(p.getTravianUid(), p);
        }

        List<WorldMapVillageResponse> rows = villages.stream()
                .map(v -> toRow(v, playersByUid.get(v.getPlayerUid())))
                .toList();

        return new WorldMapResponse(server.getMapRadius(), server.getLastMapSyncedAt(), rows.size(), rows);
    }

    private static boolean hasBox(Integer xMin, Integer xMax, Integer yMin, Integer yMax) {
        return xMin != null && xMax != null && yMin != null && yMax != null;
    }

    private static WorldMapVillageResponse toRow(MapVillage v, MapPlayer p) {
        return new WorldMapVillageResponse(
                v.getX(),
                v.getY(),
                v.getPopulation() == null ? 0 : v.getPopulation(),
                v.getName(),
                Boolean.TRUE.equals(v.getIsCapital()),
                p != null ? p.getPlayerName() : null,
                p != null ? p.getAllianceName() : null,
                p != null ? p.getTribeId() : null,
                p != null && Boolean.TRUE.equals(p.getIsInactive()),
                v.getCropperClassification()
        );
    }
}
