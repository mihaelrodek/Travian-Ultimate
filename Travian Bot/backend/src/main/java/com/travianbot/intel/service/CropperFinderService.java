package com.travianbot.intel.service;

import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only finder service over the classified {@link MapVillage} table.
 * <p>
 * <strong>Caveat — empty-tile discovery is not implemented.</strong> Travian's
 * {@code map.sql} dump only lists currently-settled tiles, so this service
 * cannot tell the user "here's an unoccupied 15c at (-12, 8)" — only "here's
 * who occupies the nearby croppers and how active they are". Adding empty-tile
 * discovery requires a per-tile scrape with rate-limiting and is deferred
 * (see {@code CropperClassifierService} javadoc).
 */
@Service
public class CropperFinderService {

    private static final Logger log = LoggerFactory.getLogger(CropperFinderService.class);

    /** Hard cap on returned candidates — keeps UI responsive on dense servers. */
    public static final int MAX_RESULTS = 200;

    /**
     * If a player's {@code lastPopChangeAt} is more recent than this cutoff
     * (and {@code isInactive=false}) we treat them as currently active. Matches
     * the {@code includeOccupied} semantics in the spec.
     */
    private static final int ACTIVE_WINDOW_DAYS = 30;

    private final MapVillageRepository mapVillageRepository;
    private final MapPlayerRepository mapPlayerRepository;
    private final ServerRepository serverRepository;
    private final VillageRepository villageRepository;

    public CropperFinderService(
            MapVillageRepository mapVillageRepository,
            MapPlayerRepository mapPlayerRepository,
            ServerRepository serverRepository,
            VillageRepository villageRepository
    ) {
        this.mapVillageRepository = mapVillageRepository;
        this.mapPlayerRepository = mapPlayerRepository;
        this.serverRepository = serverRepository;
        this.villageRepository = villageRepository;
    }

    /**
     * Search for likely-cropper villages within {@code maxDistanceFields} of
     * the user's pivot village, optionally filtered by classification bucket.
     *
     * @param serverId tracked Travian server whose map the search runs against
     * @param nearVillageId pivot village id (must belong to an account on this server)
     * @param maxDistanceFields radius in map fields (Euclidean)
     * @param wantedClassifications buckets to keep; null/empty means "all known"
     * @param includeOccupied if false, drop villages whose player still looks active
     * @return up to {@link #MAX_RESULTS} candidates sorted by ascending distance
     */
    @Transactional(readOnly = true)
    public List<CropperCandidate> findNearVillage(
            Long serverId,
            Long nearVillageId,
            double maxDistanceFields,
            Set<String> wantedClassifications,
            boolean includeOccupied
    ) {
        if (serverId == null) throw new IllegalArgumentException("serverId is required");
        if (nearVillageId == null) throw new IllegalArgumentException("nearVillageId is required");
        if (maxDistanceFields <= 0) throw new IllegalArgumentException("maxDistanceFields must be positive");

        Optional<Village> pivotOpt = villageRepository.findById(nearVillageId);
        if (pivotOpt.isEmpty()) {
            log.debug("findNearVillage: pivot village {} not found", nearVillageId);
            return List.of();
        }
        Village pivot = pivotOpt.get();

        // Pivot must belong to an account on THIS server — otherwise the
        // result set is just data for the wrong map's coordinates. Mirror the
        // InactiveSearchService contract and throw rather than silently
        // returning a useless list.
        Optional<Server> serverOpt = serverRepository.findById(serverId);
        if (serverOpt.isEmpty()) {
            log.debug("findNearVillage: server {} not found", serverId);
            return List.of();
        }
        String serverCanonical = serverOpt.get().getUrl();
        String pivotServerCanonical = Server.canonicalUrl(pivot.getAccount().getServerUrl());
        if (serverCanonical == null || !serverCanonical.equals(pivotServerCanonical)) {
            throw new IllegalArgumentException("pivot village belongs to a different server");
        }

        int px = pivot.getCoordinateX();
        int py = pivot.getCoordinateY();

        Set<String> wanted = (wantedClassifications == null || wantedClassifications.isEmpty())
                ? null
                : new HashSet<>(wantedClassifications);

        // Bounding-box pre-filter: anything outside [px-r, px+r]×[py-r, py+r]
        // is too far by construction. The Euclidean check below trims the
        // box's corners. Cast up to avoid int overflow on extreme inputs.
        int radius = (int) Math.ceil(maxDistanceFields);
        int xMin = px - radius, xMax = px + radius;
        int yMin = py - radius, yMax = py + radius;
        List<MapVillage> candidates = mapVillageRepository
                .findAllByServerIdAndXBetweenAndYBetween(serverId, xMin, xMax, yMin, yMax);

        // Pull every player referenced by the candidates in one lookup, then
        // index by uid. Cheaper than N round-trips when the box has lots of villages.
        Set<Long> uids = new HashSet<>();
        for (MapVillage v : candidates) uids.add(v.getPlayerUid());
        Map<Long, MapPlayer> playerByUid = new HashMap<>();
        for (Long uid : uids) {
            mapPlayerRepository.findFirstByServerIdAndTravianUid(serverId, uid)
                    .ifPresent(p -> playerByUid.put(uid, p));
        }

        Instant activeCutoff = Instant.now().minus(Duration.ofDays(ACTIVE_WINDOW_DAYS));

        List<CropperCandidate> out = new ArrayList<>();
        for (MapVillage v : candidates) {
            String bucket = v.getCropperClassification();
            if (bucket == null) continue;
            // Always skip plain UNKNOWN — the user wants candidates, not noise.
            if (CropperClassifierService.UNKNOWN.equals(bucket)) continue;
            if (wanted != null && !wanted.contains(bucket)) continue;

            double dist = distance(v.getX(), v.getY(), px, py);
            if (dist > maxDistanceFields) continue;

            MapPlayer player = playerByUid.get(v.getPlayerUid());
            boolean looksActive = playerLooksActive(player, activeCutoff);
            if (!includeOccupied && looksActive) continue;

            out.add(new CropperCandidate(
                    v.getId(),
                    v.getPlayerUid(),
                    player == null ? null : player.getPlayerName(),
                    player == null ? null : player.getAllianceName(),
                    player == null ? null : player.getTribeId(),
                    v.getX(),
                    v.getY(),
                    v.getPopulation(),
                    Boolean.TRUE.equals(v.getIsCapital()),
                    bucket,
                    dist,
                    player == null ? null : player.getVillageCount(),
                    looksActive
            ));
        }

        out.sort(Comparator.comparingDouble(CropperCandidate::distanceFields));
        if (out.size() > MAX_RESULTS) {
            return out.subList(0, MAX_RESULTS);
        }
        return out;
    }

    /**
     * A player "looks active" when intel's inactive-flag is false AND we've
     * seen a population change in the last {@link #ACTIVE_WINDOW_DAYS} days
     * (or we haven't accumulated enough history to call them idle yet).
     * Missing player rows are treated as inactive (nothing to dispute).
     */
    private boolean playerLooksActive(MapPlayer p, Instant activeCutoff) {
        if (p == null) return false;
        if (Boolean.TRUE.equals(p.getIsInactive())) return false;
        Instant lastChange = p.getLastPopChangeAt();
        if (lastChange == null) {
            // No observed change ever — if we've seen them for >activeWindow
            // they're effectively idle, otherwise we don't know.
            return p.getFirstSeenAt() != null && p.getFirstSeenAt().isAfter(activeCutoff);
        }
        return lastChange.isAfter(activeCutoff);
    }

    private static double distance(int ax, int ay, int bx, int by) {
        double dx = ax - bx;
        double dy = ay - by;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * One ranked cropper-candidate row. The id refers to {@link MapVillage}'s
     * primary key; {@code playerUid} is the in-game uid so the UI can deep-link
     * to the player profile.
     */
    public record CropperCandidate(
            Long mapVillageId,
            Long playerUid,
            String playerName,
            String allianceName,
            Integer tribeId,
            Integer x,
            Integer y,
            Integer population,
            Boolean isCapital,
            String cropperClassification,
            Double distanceFields,
            Integer playerVillageCount,
            Boolean playerLooksActive
    ) {}
}
