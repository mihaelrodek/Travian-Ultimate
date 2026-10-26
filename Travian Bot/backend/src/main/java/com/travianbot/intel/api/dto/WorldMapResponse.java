package com.travianbot.intel.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full payload for the world-map view: the server's map extent plus every
 * settled village. {@code mapRadius} is the half-width in fields (e.g. 200 →
 * coordinates span -200..200), which the canvas uses to frame the world;
 * {@code lastSyncedAt} lets the UI show how fresh the data is and prompt a
 * re-sync. {@code map.sql} only lists <em>occupied</em> tiles, so empty land
 * (including unsettled croppers) is simply absent from {@link #villages}.
 */
public record WorldMapResponse(
        Integer mapRadius,
        Instant lastSyncedAt,
        int villageCount,
        List<WorldMapVillageResponse> villages
) {
}
