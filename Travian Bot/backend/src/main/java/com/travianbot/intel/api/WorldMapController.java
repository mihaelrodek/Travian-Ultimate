package com.travianbot.intel.api;

import com.travianbot.intel.api.dto.WorldMapResponse;
import com.travianbot.intel.service.WorldMapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the interactive world map (roadmap MAP1) from the parsed {@code map.sql}
 * data. One call returns every settled village on the server with the attributes
 * the canvas needs to colour and label it. Optional bounding-box params narrow
 * the result to a viewport when a full server is too large to ship at once.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/intel/map")
public class WorldMapController {

    private final WorldMapService worldMapService;

    public WorldMapController(WorldMapService worldMapService) {
        this.worldMapService = worldMapService;
    }

    @GetMapping
    public WorldMapResponse map(
            @PathVariable Long serverId,
            @RequestParam(required = false) Integer xMin,
            @RequestParam(required = false) Integer xMax,
            @RequestParam(required = false) Integer yMin,
            @RequestParam(required = false) Integer yMax
    ) {
        return worldMapService.mapForServer(serverId, xMin, xMax, yMin, yMax);
    }
}
