package com.travianbot.aianalysis.api.dto;

import com.travianbot.aianalysis.domain.OasisScan;
import com.travianbot.aianalysis.domain.OasisTile;
import com.travianbot.aianalysis.domain.OasisType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the AI Analysis (oasis scan) feature. Kept under one final class
 * because the UI consumes them as one response shape — bundling into a single
 * file means one import on the frontend and reads naturally as "the
 * AI analysis API surface".
 */
public final class OasisScanDtos {

    private OasisScanDtos() {
    }

    public record StartScanRequest(
            @Min(0) @Max(800) int centerX,
            @Min(0) @Max(800) int centerY,
            @Min(1) @Max(20) int radius
    ) {
    }

    public record OasisTileDto(
            int x,
            int y,
            OasisType type,
            int bonusPct,
            int distanceTiles,
            String occupiedByPlayer
    ) {
        public static OasisTileDto from(OasisTile t) {
            return new OasisTileDto(
                    t.getX(), t.getY(), t.getType(), t.getBonusPct(),
                    t.getDistanceTiles(), t.getOccupiedByPlayer()
            );
        }
    }

    public record OasisScanResponse(
            Long id,
            Long accountId,
            int centerX,
            int centerY,
            int radius,
            Instant startedAt,
            Instant completedAt,
            int woodCount,
            int clayCount,
            int ironCount,
            int cropCount,
            int mixedCount,
            List<OasisTileDto> tiles
    ) {
        public static OasisScanResponse from(OasisScan s) {
            return new OasisScanResponse(
                    s.getId(),
                    s.getAccount().getId(),
                    s.getCenterX(),
                    s.getCenterY(),
                    s.getRadius(),
                    s.getStartedAt(),
                    s.getCompletedAt(),
                    nullSafe(s.getWoodCount()),
                    nullSafe(s.getClayCount()),
                    nullSafe(s.getIronCount()),
                    nullSafe(s.getCropCount()),
                    nullSafe(s.getMixedCount()),
                    s.getTiles().stream().map(OasisTileDto::from).toList()
            );
        }

        private static int nullSafe(Integer v) { return v == null ? 0 : v; }
    }
}
