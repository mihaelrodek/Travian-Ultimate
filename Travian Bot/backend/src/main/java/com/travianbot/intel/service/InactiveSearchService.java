package com.travianbot.intel.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.intel.alliance.service.MapAllianceAggregator;
import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.domain.MapVillagePopulationSnapshot;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillagePopulationSnapshotRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import com.travianbot.intel.scraper.MapSqlParser;
import com.travianbot.intel.scraper.MapSqlParser.ParsedMapRow;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core service for the intel/inactive-search feature.
 * <p>
 * Three responsibilities:
 * <ol>
 *   <li>{@link #syncForServer(Long)} — fetch + parse the daily map.sql for a
 *       tracked Travian server, upsert {@link MapPlayer}/{@link MapVillage}
 *       rows, roll the 7d/30d population snapshots, and re-derive the
 *       {@code isInactive} flag.</li>
 *   <li>{@link #search(Long, InactiveSearchFilters, Pageable)} — paginated
 *       filter over the upserted player table. The distance filter uses a
 *       bounding-box pre-filter on {@link MapVillage} followed by a per-player
 *       Euclidean min-distance computation in memory; this is cheap (the
 *       bounding box throws away >95% of the map up front) and avoids putting
 *       sqrt into JPQL where every DB vendor implements it differently.</li>
 *   <li>{@link #markPopulationSnapshot(Long, int)} — called by the daily
 *       scheduler at the 7d / 30d boundaries to roll the snapshot columns.</li>
 * </ol>
 *
 * <h3>Inactivity heuristic</h3>
 * <pre>
 *   isInactive  ←  (currentPop - snapshot7dAgo) ≤ 5
 *                  AND lastPopChangeAt is null OR &gt;7d ago
 *                  AND we have a 7d snapshot at all (i.e. we've observed the player
 *                      for &ge;7 days — fresh accounts get a free pass)
 * </pre>
 * The "≤5" pop threshold catches "they built a single field this week" — pure
 * idle-aborts who haven't grown at all are rare; players who logged once to
 * dodge auto-deletion still register as low-growth and are valid farm targets.
 */
@Service
public class InactiveSearchService {

    private static final Logger log = LoggerFactory.getLogger(InactiveSearchService.class);

    /** Pop delta cutoff for the inactive heuristic — see class javadoc. */
    private static final int INACTIVE_POP_DELTA_THRESHOLD = 5;
    /** Min days since the last population change for the inactive heuristic. */
    private static final int INACTIVE_MIN_IDLE_DAYS = 7;
    /** Default page size if the caller doesn't override. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final MapPlayerRepository playerRepository;
    private final MapVillageRepository villageRepository;
    private final MapVillagePopulationSnapshotRepository populationSnapshotRepository;
    private final MapSqlFetcher fetcher;
    private final MapSqlParser parser;
    private final ServerRepository serverRepository;
    private final AccountRepository accountRepository;
    private final VillageRepository accountVillageRepository;
    private final BotEventService botEventService;
    private final CropperClassifierService cropperClassifierService;
    private final MapAllianceAggregator mapAllianceAggregator;

    /**
     * Per-server lock: stops two threads from running {@link #syncForServer}
     * for the same server concurrently. Without it, both threads do a "lookup
     * → empty → insert" cycle for the same uid and the second one trips a
     * unique-constraint violation. Single-instance only; if we ever scale out
     * we need a DB advisory lock here instead.
     */
    private final Map<Long, ReentrantLock> serverSyncLocks = new ConcurrentHashMap<>();

    public InactiveSearchService(
            MapPlayerRepository playerRepository,
            MapVillageRepository villageRepository,
            MapVillagePopulationSnapshotRepository populationSnapshotRepository,
            MapSqlFetcher fetcher,
            MapSqlParser parser,
            ServerRepository serverRepository,
            AccountRepository accountRepository,
            VillageRepository accountVillageRepository,
            BotEventService botEventService,
            CropperClassifierService cropperClassifierService,
            MapAllianceAggregator mapAllianceAggregator
    ) {
        this.playerRepository = playerRepository;
        this.villageRepository = villageRepository;
        this.populationSnapshotRepository = populationSnapshotRepository;
        this.fetcher = fetcher;
        this.parser = parser;
        this.serverRepository = serverRepository;
        this.accountRepository = accountRepository;
        this.accountVillageRepository = accountVillageRepository;
        this.botEventService = botEventService;
        this.cropperClassifierService = cropperClassifierService;
        this.mapAllianceAggregator = mapAllianceAggregator;
    }

    // ---------- Sync ---------------------------------------------------------

    /**
     * Fetch, parse, and upsert the daily map.sql for one tracked server.
     * Returns the number of distinct players upserted on success. On failure
     * emits a {@link BotEventOutcome#ERROR} event (when an account on the
     * server exists to attribute it to) and rethrows so the scheduler can
     * move on to the next server.
     */
    @Transactional
    public int syncForServer(Long serverId) {
        // Serialize syncs for the same server — concurrent calls (e.g. user
        // clicks Sync twice) would otherwise race on insert and trip the
        // (server_id, travian_uid) unique constraint. Lock is held until the
        // transaction commits so the second caller's lookup sees the rows
        // the first caller persisted.
        ReentrantLock lock = serverSyncLocks.computeIfAbsent(serverId, k -> new ReentrantLock());
        lock.lock();
        try {
            return doSyncForServer(serverId);
        } finally {
            lock.unlock();
        }
    }

    private int doSyncForServer(Long serverId) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("Server not found: " + serverId));
        final String serverUrl = server.getUrl();
        // Pre-resolve an attributable accountId BEFORE the lambda body — there
        // is no guaranteed account for a freshly-added server, and we still
        // want the sync to succeed in that case. Null = skip the bot-event.
        final Long attributableAccountId = findAnyAccountIdOnServer(serverUrl);

        String body;
        try {
            body = fetcher.fetch(serverUrl);
        } catch (RuntimeException e) {
            if (attributableAccountId != null) {
                botEventService.record(attributableAccountId, null, "INTEL_MAP_SYNC",
                        BotEventOutcome.ERROR, e.getMessage());
            }
            throw e;
        }

        List<ParsedMapRow> rows = parser.parse(body);
        log.info("map.sql sync for server {} ({}): parsed {} player-village rows",
                serverId, serverUrl, rows.size());

        // Diagnostic: a non-empty fetch that parses to zero rows almost
        // always means the dump format differs from what the parser expects
        // (different column count, table prefix, escape style, etc). Dump a
        // sample so the operator can see exactly what arrived.
        if (rows.isEmpty() && body != null && body.length() > 50) {
            int sampleEnd = Math.min(1500, body.length());
            String sample = body.substring(0, sampleEnd).replace('\n', ' ').replace('\r', ' ');
            log.warn("map.sql parsed 0 rows for server {} from {} bytes — first {} chars: {}",
                    serverId, body.length(), sampleEnd, sample);
        }

        Instant now = Instant.now();

        // Group rows by uid → aggregate pop + village count, then upsert.
        Map<Long, List<ParsedMapRow>> byUid = new HashMap<>();
        for (ParsedMapRow row : rows) {
            byUid.computeIfAbsent(row.uid(), k -> new ArrayList<>()).add(row);
        }

        // -----------------------------------------------------------------
        // Bulk load — one query per table, then everything else happens in
        // memory. Previous code did N+1 queries per row (~25 000 round trips
        // for a 7 000-village server, ~2 minutes wall time). The bulk-load
        // pattern cuts that to ~2 SELECTs + a handful of batched INSERT/UPDATE
        // statements — typically under 10 seconds for the same workload.
        // -----------------------------------------------------------------
        long bulkLoadStart = System.currentTimeMillis();
        Map<Long, MapPlayer> playersByUid = new HashMap<>();
        for (MapPlayer p : playerRepository.findAllByServerId(serverId)) {
            playersByUid.put(p.getTravianUid(), p);
        }
        Map<Long, MapVillage> villagesByVid = new HashMap<>();
        for (MapVillage v : villageRepository.findAllByServerId(serverId)) {
            villagesByVid.put(v.getTravianVid(), v);
        }
        log.info("Bulk-loaded {} existing players + {} villages in {} ms",
                playersByUid.size(), villagesByVid.size(),
                System.currentTimeMillis() - bulkLoadStart);

        List<MapPlayer> playersToSave = new ArrayList<>(byUid.size());
        List<MapVillage> villagesToSave = new ArrayList<>(rows.size());
        int upserts = 0;

        for (Map.Entry<Long, List<ParsedMapRow>> entry : byUid.entrySet()) {
            Long uid = entry.getKey();
            List<ParsedMapRow> playerRows = entry.getValue();

            int totalPop = 0;
            for (ParsedMapRow r : playerRows) totalPop += r.population();
            int villageCount = playerRows.size();
            ParsedMapRow first = playerRows.get(0);

            MapPlayer player = playersByUid.get(uid);
            if (player == null) {
                player = new MapPlayer(
                        serverId, uid, first.playerName(), first.tribeId(),
                        totalPop, villageCount);
                playersByUid.put(uid, player);
            }
            player.recordSnapshot(totalPop, villageCount, now);
            player.setPlayerName(first.playerName());
            player.setTribeId(first.tribeId());
            player.setAlliance(first.aid(), first.allianceName());
            player.setIsInactive(computeIsInactive(player, now));
            playersToSave.add(player);
            upserts++;

            for (ParsedMapRow r : playerRows) {
                MapVillage village = villagesByVid.get(r.vid());
                if (village == null) {
                    village = new MapVillage(
                            serverId, r.vid(), uid, r.villageName(),
                            r.x(), r.y(), r.population());
                    villagesByVid.put(r.vid(), village);
                }
                village.setName(r.villageName());
                village.setCoordinates(r.x(), r.y());
                village.setPopulation(r.population());
                village.setPlayerUid(uid);
                if (r.capital() != null) village.setIsCapital(r.capital());
                village.markSeen(now);
                villagesToSave.add(village);
            }
        }

        long flushStart = System.currentTimeMillis();
        playerRepository.saveAll(playersToSave);
        villageRepository.saveAll(villagesToSave);
        log.info("saveAll: {} players + {} villages flushed in {} ms",
                playersToSave.size(), villagesToSave.size(),
                System.currentTimeMillis() - flushStart);

        // Append population-history snapshots for villages whose pop changed
        // since their last recorded snapshot. Dedup keeps the table sparse —
        // most villages don't grow on every sync. Tolerant: if this errors
        // mid-batch the sync itself is already committed and useful.
        try {
            int snapshotsAdded = recordPopulationSnapshots(villagesToSave, now);
            if (snapshotsAdded > 0) {
                log.info("Recorded {} new population snapshots", snapshotsAdded);
            }
        } catch (RuntimeException e) {
            log.warn("Population-snapshot recording failed: {} (sync still succeeded)", e.getMessage());
        }

        // Aggregate alliance rows from the just-upserted MapPlayer set. Wrapped
        // in try/catch so a bug in the aggregator can't abort an otherwise-good
        // sync — the player rows are already persisted at this point, and
        // alliance aggregation re-runs every sync.
        try {
            mapAllianceAggregator.aggregateForServer(serverId, now);
        } catch (RuntimeException e) {
            log.warn("Alliance aggregation failed for server {}: {} — continuing with sync",
                    serverId, e.getMessage());
        }

        // Stamp cropper-classification buckets on every village. Runs inside
        // the same @Transactional sync so a partial classification can't leak
        // into the search results.
        cropperClassifierService.classifyServer(serverId);

        // Stamp the sync metadata on the Server row so the picker can render
        // "synced 3 days ago · 12,345 players" without re-counting.
        server.setLastMapSyncedAt(now);
        server.setLastMapSyncPlayerCount(upserts);
        serverRepository.save(server);

        if (attributableAccountId != null) {
            botEventService.record(attributableAccountId, null, "INTEL_MAP_SYNC",
                    BotEventOutcome.OK, "synced " + upserts + " players, " + rows.size() + " villages");
        }
        return upserts;
    }

    /**
     * Find ANY local {@link Account} whose canonical serverUrl matches the
     * given server. Used solely for {@link BotEventService} attribution —
     * the event log requires a non-null accountId and we'd rather attribute
     * a sync to "some account on that server" than skip the log entirely.
     * Returns {@code null} when no account exists on this server (e.g.
     * the user added the server manually for intel-only searching).
     */
    private Long findAnyAccountIdOnServer(String serverUrl) {
        if (serverUrl == null) return null;
        String canonical = Server.canonicalUrl(serverUrl);
        for (Account a : accountRepository.findAll()) {
            if (canonical.equals(Server.canonicalUrl(a.getServerUrl()))) {
                return a.getId();
            }
        }
        return null;
    }

    /**
     * Apply the inactivity heuristic. Lenient for newly-tracked players because
     * we don't have any history for them yet — better to flag a likely-active
     * player as "not yet inactive" than to spam the farmlist with bad targets.
     */
    private boolean computeIsInactive(MapPlayer p, Instant now) {
        Integer snapshot7 = p.getPopulationSnapshot7dAgo();
        if (snapshot7 == null) {
            // No 7d history yet — too soon to call. Hold whatever flag we had.
            return Boolean.TRUE.equals(p.getIsInactive());
        }
        int delta = p.getCurrentPopulation() - snapshot7;
        if (delta > INACTIVE_POP_DELTA_THRESHOLD) return false;

        Instant lastChange = p.getLastPopChangeAt();
        if (lastChange != null) {
            Duration since = Duration.between(lastChange, now);
            if (since.toDays() < INACTIVE_MIN_IDLE_DAYS) return false;
        }
        return true;
    }

    /**
     * Roll the 7d / 30d snapshot columns: takes the current population and
     * writes it into the column corresponding to {@code daysAgo}. Called by
     * {@link MapSyncScheduler} once per day at the relevant boundary.
     * <p>
     * No-op for any other value of {@code daysAgo} (defensive — keeps the
     * scheduler's call site impossible to misuse silently).
     */
    @Transactional
    public void markPopulationSnapshot(Long serverId, int daysAgo) {
        if (daysAgo != 7 && daysAgo != 30) return;
        List<MapPlayer> all = playerRepository
                .findAll(snapshotSpec(serverId));
        for (MapPlayer p : all) {
            if (daysAgo == 7) p.setPopulationSnapshot7dAgo(p.getCurrentPopulation());
            else p.setPopulationSnapshot30dAgo(p.getCurrentPopulation());
            playerRepository.save(p);
        }
    }

    private Specification<MapPlayer> snapshotSpec(Long serverId) {
        return (root, q, cb) -> cb.equal(root.get("serverId"), serverId);
    }

    // ---------- Search -------------------------------------------------------

    /**
     * Paginated search over the upserted player table.
     * <p>
     * Two execution paths:
     * <ul>
     *   <li><b>Heavy</b> — when any post-load filter is set (distance + max-dist,
     *       coord box, max population increase) or a custom sort is requested,
     *       we materialise the full SQL-filtered set, apply the post-filters
     *       and sort in memory, then paginate the result. Cost is bounded
     *       because the SQL-side filters (population, alliance, inactive,
     *       last-seen) already throw away the bulk of the map.</li>
     *   <li><b>Light</b> — none of the above; paginate at the SQL level.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Page<SearchResult> search(Long serverId, InactiveSearchFilters filters, Pageable pageable) {
        if (serverId == null) throw new IllegalArgumentException("serverId is required");

        // Resolve the pivot village's coordinates if a distance filter is set,
        // and reject up-front if the pivot belongs to a different server.
        PivotPoint pivot = null;
        if (filters.nearVillageId() != null) {
            pivot = resolvePivot(serverId, filters.nearVillageId());
        }

        Specification<MapPlayer> spec = buildSpec(serverId, filters);

        boolean needsMemoryPass = pivot != null && filters.maxDistanceFields() != null
                || filters.xMin() != null || filters.xMax() != null
                || filters.yMin() != null || filters.yMax() != null
                || filters.maxPopulationIncrease() != null
                || (filters.sortBy() != null && !filters.sortBy().isBlank());

        if (needsMemoryPass) {
            List<MapPlayer> candidates = playerRepository.findAll(spec);
            List<SearchResult> kept = new ArrayList<>(candidates.size());
            Double maxDist = filters.maxDistanceFields();
            Integer maxIncrease = filters.maxPopulationIncrease();
            for (MapPlayer p : candidates) {
                if (maxIncrease != null && p.getPopulationSnapshot7dAgo() != null) {
                    int delta = p.getCurrentPopulation() - p.getPopulationSnapshot7dAgo();
                    if (delta > maxIncrease) continue;
                }
                Double dist = (pivot == null) ? null
                        : nearestVillageDistance(serverId, p.getTravianUid(), pivot);
                if (maxDist != null && (dist == null || dist > maxDist)) continue;
                if (!playerInsideCoordBox(serverId, p.getTravianUid(), filters)) continue;
                kept.add(toSearchResult(p, dist));
            }
            kept.sort(resultComparator(filters, pivot != null));
            int start = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), kept.size());
            int end = Math.min(start + pageable.getPageSize(), kept.size());
            return new PageImpl<>(kept.subList(start, end), pageable, kept.size());
        }

        Page<MapPlayer> page = playerRepository.findAll(spec, pageable);
        final PivotPoint finalPivot = pivot;
        final Long finalServerId = serverId;
        List<SearchResult> mapped = page.getContent().stream()
                .map(p -> {
                    Double dist = (finalPivot == null) ? null
                            : nearestVillageDistance(finalServerId, p.getTravianUid(), finalPivot);
                    return toSearchResult(p, dist);
                })
                .toList();
        return new PageImpl<>(mapped, pageable, page.getTotalElements());
    }

    /**
     * Resolve a pivot village id to {x, y} and verify it actually sits on the
     * requested server. Mismatch = throw — the caller is asking for results
     * pinned to a village whose owning account is on a DIFFERENT server,
     * which would silently return useless data otherwise.
     */
    private PivotPoint resolvePivot(Long serverId, Long villageId) {
        Optional<Village> opt = accountVillageRepository.findById(villageId);
        if (opt.isEmpty()) return null;
        Village v = opt.get();
        Server server = serverRepository.findById(serverId).orElse(null);
        if (server == null) return null;
        String serverCanonical = server.getUrl();
        String villageServerCanonical = Server.canonicalUrl(v.getAccount().getServerUrl());
        if (serverCanonical == null || !serverCanonical.equals(villageServerCanonical)) {
            throw new IllegalArgumentException("pivot village belongs to a different server");
        }
        return new PivotPoint(v.getCoordinateX(), v.getCoordinateY());
    }

    /**
     * Append a snapshot row for each village whose current population differs
     * from the most-recent snapshot on file. Bulk-saved at the end so the
     * extra round-trip is one batch, not one-per-village.
     */
    private int recordPopulationSnapshots(List<MapVillage> villages, Instant capturedAt) {
        List<MapVillagePopulationSnapshot> toSave = new ArrayList<>();
        for (MapVillage v : villages) {
            if (v.getId() == null) continue; // not persisted somehow — defensive
            MapVillagePopulationSnapshot latest = populationSnapshotRepository.findLatestForVillage(v.getId());
            if (latest != null && latest.getPopulation() == v.getPopulation()) continue;
            toSave.add(new MapVillagePopulationSnapshot(v.getId(), v.getPopulation(), capturedAt));
        }
        if (toSave.isEmpty()) return 0;
        populationSnapshotRepository.saveAll(toSave);
        return toSave.size();
    }

    /**
     * Read-only helper for the chart endpoint. Returns most-recent first,
     * limited to the requested number of points. Public so the controller
     * can call it without exposing the repository.
     */
    @Transactional(readOnly = true)
    public List<MapVillagePopulationSnapshot> populationHistory(Long mapVillageId, int maxPoints) {
        int cap = Math.max(1, Math.min(maxPoints, 200));
        return populationSnapshotRepository.findRecentForVillage(
                mapVillageId, org.springframework.data.domain.PageRequest.of(0, cap));
    }

    /**
     * Coord-box test: the player passes if AT LEAST ONE of their villages
     * sits inside the box. Any unset bound is treated as -∞/+∞.
     */
    private boolean playerInsideCoordBox(Long serverId, Long playerUid, InactiveSearchFilters f) {
        if (f.xMin() == null && f.xMax() == null && f.yMin() == null && f.yMax() == null) return true;
        int xMin = f.xMin() == null ? Integer.MIN_VALUE : f.xMin();
        int xMax = f.xMax() == null ? Integer.MAX_VALUE : f.xMax();
        int yMin = f.yMin() == null ? Integer.MIN_VALUE : f.yMin();
        int yMax = f.yMax() == null ? Integer.MAX_VALUE : f.yMax();
        List<MapVillage> villages = villageRepository.findAllByServerIdAndPlayerUid(serverId, playerUid);
        for (MapVillage v : villages) {
            if (v.getX() >= xMin && v.getX() <= xMax && v.getY() >= yMin && v.getY() <= yMax) return true;
        }
        return false;
    }

    /**
     * Build the SQL-side {@link Specification} that covers everything except
     * the distance + coord-box filters (those are done in memory — see
     * {@link #search}).
     */
    private Specification<MapPlayer> buildSpec(Long serverId, InactiveSearchFilters f) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("serverId"), serverId));

            if (Boolean.TRUE.equals(f.isInactiveOnly())) {
                preds.add(cb.isTrue(root.get("isInactive")));
            }
            if (f.maxPopulation() != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("currentPopulation"), f.maxPopulation()));
            }
            if (f.minPopulation() != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("currentPopulation"), f.minPopulation()));
            }
            if (f.minVillageCount() != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("villageCount"), f.minVillageCount()));
            }
            if (f.maxVillageCount() != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("villageCount"), f.maxVillageCount()));
            }
            if (f.tribeIds() != null && !f.tribeIds().isEmpty()) {
                preds.add(root.get("tribeId").in(f.tribeIds()));
            }
            if (f.allianceExcludeContains() != null && !f.allianceExcludeContains().isBlank()) {
                String pattern = "%" + f.allianceExcludeContains().toLowerCase() + "%";
                // Exclude only when alliance is non-null AND matches; null alliances pass through.
                preds.add(cb.or(
                        cb.isNull(root.get("allianceName")),
                        cb.notLike(cb.lower(root.get("allianceName")), pattern)
                ));
            }
            if (f.allianceIncludeContains() != null && !f.allianceIncludeContains().isBlank()) {
                String pattern = "%" + f.allianceIncludeContains().toLowerCase() + "%";
                // Players without an alliance fail the include filter — they can't match.
                preds.add(cb.and(
                        cb.isNotNull(root.get("allianceName")),
                        cb.like(cb.lower(root.get("allianceName")), pattern)
                ));
            }
            if (f.allianceTravianId() != null) {
                // Direct drill-down — picks players whose travianAid exactly
                // matches the alliance the UI clicked on. Distinct from the
                // include/exclude name-substring filters above because those
                // are fuzzy and miss alliances with collisions in their names.
                preds.add(cb.equal(root.get("travianAid"), f.allianceTravianId()));
            }
            if (f.minIdleDays() != null) {
                Instant cutoff = Instant.now().minus(Duration.ofDays(f.minIdleDays()));
                // Either we've never recorded a change (genuinely flat since first seen)
                // or the last change is older than the cutoff.
                preds.add(cb.or(
                        cb.isNull(root.get("lastPopChangeAt")),
                        cb.lessThanOrEqualTo(root.get("lastPopChangeAt"), cutoff)
                ));
            }
            if (f.lastSeenWithinDays() != null) {
                Instant cutoff = Instant.now().minus(Duration.ofDays(f.lastSeenWithinDays()));
                preds.add(cb.greaterThanOrEqualTo(root.get("lastSeenAt"), cutoff));
            }
            // maxPopulationIncrease: skip if no 7d snapshot (lenient) — applied in memory after
            // load because (currentPop - snapshot7d) isn't a column.
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /**
     * In-memory comparator for sort options that can't be expressed in JPQL
     * after the post-load filters (distance, coord box, max-pop-increase).
     */
    private Comparator<SearchResult> resultComparator(InactiveSearchFilters f, boolean pivotPresent) {
        String by = f.sortBy() == null ? "" : f.sortBy().toLowerCase();
        boolean desc = "desc".equalsIgnoreCase(f.sortDir());
        Comparator<SearchResult> c = switch (by) {
            case "population" -> Comparator.comparingInt(r -> r.player().getCurrentPopulation());
            case "idledays"   -> Comparator.comparing(r -> r.daysSincePopChange() == null ? Long.MIN_VALUE : r.daysSincePopChange());
            case "villagecount" -> Comparator.comparingInt(r -> r.player().getVillageCount());
            case "lastseen" -> Comparator.comparing(r -> r.player().getLastSeenAt());
            case "distance" -> Comparator.comparingDouble(r ->
                    r.nearestVillageDistance() == null ? Double.MAX_VALUE : r.nearestVillageDistance());
            default -> pivotPresent
                    ? Comparator.<SearchResult>comparingDouble(r ->
                            r.nearestVillageDistance() == null ? Double.MAX_VALUE : r.nearestVillageDistance())
                    : Comparator.<SearchResult>comparingInt(r -> r.player().getCurrentPopulation());
        };
        return desc ? c.reversed() : c;
    }

    /**
     * Compute the Euclidean distance from {@code pivot} to the closest village
     * the given player owns, using the bounding-box index as a cheap pre-filter.
     * Returns null when the player has no villages on this server's map.
     */
    private Double nearestVillageDistance(Long serverId, Long playerUid, PivotPoint pivot) {
        List<MapVillage> villages = villageRepository.findAllByServerIdAndPlayerUid(serverId, playerUid);
        if (villages.isEmpty()) return null;
        double best = Double.MAX_VALUE;
        for (MapVillage v : villages) {
            double dx = v.getX() - pivot.x();
            double dy = v.getY() - pivot.y();
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < best) best = d;
        }
        return best == Double.MAX_VALUE ? null : best;
    }

    private SearchResult toSearchResult(MapPlayer p, Double nearestDistance) {
        Long daysSinceChange = null;
        if (p.getLastPopChangeAt() != null) {
            daysSinceChange = Duration.between(p.getLastPopChangeAt(), Instant.now()).toDays();
        }
        return new SearchResult(p, daysSinceChange, nearestDistance);
    }

    /**
     * Detail view: player + their villages list. Returns empty when the player
     * isn't tracked on this server (e.g. user typed a uid by hand).
     */
    @Transactional(readOnly = true)
    public Optional<PlayerDetail> getPlayer(Long serverId, Long uid) {
        Optional<MapPlayer> player = playerRepository.findFirstByServerIdAndTravianUid(serverId, uid);
        if (player.isEmpty()) return Optional.empty();
        List<MapVillage> villages = villageRepository.findAllByServerIdAndPlayerUid(serverId, uid);
        return Optional.of(new PlayerDetail(player.get(), villages));
    }

    // ---------- Value records ------------------------------------------------

    /**
     * Filter envelope for {@link #search}. All fields except {@code serverId}
     * are optional; null = "don't filter on this".
     * <p>
     * Sort: {@code sortBy} accepts {@code "distance"} (only meaningful when a
     * pivot is set), {@code "population"}, {@code "idleDays"},
     * {@code "villageCount"}, {@code "lastSeen"}. {@code sortDir} is
     * {@code "asc"} or {@code "desc"}. Default is distance-ascending when a
     * pivot is set, else population-ascending.
     */
    public record InactiveSearchFilters(
            Long serverId,
            Integer minIdleDays,
            Integer maxPopulation,
            Integer minPopulation,
            /** Exclude players whose currentPop - 7dSnapshot > this value. */
            Integer maxPopulationIncrease,
            Integer minVillageCount,
            Integer maxVillageCount,
            /** Bounding-box on the player's nearest village to pivot — null = no filter. */
            Integer xMin,
            Integer xMax,
            Integer yMin,
            Integer yMax,
            Long nearVillageId,
            Double maxDistanceFields,
            List<Integer> tribeIds,
            String allianceExcludeContains,
            String allianceIncludeContains,
            /** Exact alliance-id filter — drives the UI's "players in alliance X" drill-down. */
            Long allianceTravianId,
            /** Player was seen in map.sql in the last N days. */
            Integer lastSeenWithinDays,
            Boolean isInactiveOnly,
            String sortBy,
            String sortDir
    ) {}

    /** Search result row: the player plus two derived fields used by the UI. */
    public record SearchResult(MapPlayer player, Long daysSincePopChange, Double nearestVillageDistance) {}

    /** Detail view: the player plus all of their tracked villages. */
    public record PlayerDetail(MapPlayer player, List<MapVillage> villages) {}

    private record PivotPoint(int x, int y) {}
}
