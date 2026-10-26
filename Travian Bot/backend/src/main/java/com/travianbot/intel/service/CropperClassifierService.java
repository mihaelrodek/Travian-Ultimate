package com.travianbot.intel.service;

import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-sync pass that stamps a heuristic {@code cropperClassification} bucket on
 * every {@link MapVillage} for a tracked server. Runs as a quick sweep after
 * {@link InactiveSearchService#syncForServer(Long)} finishes upserting rows.
 *
 * <h3>Heuristic</h3>
 * Travian's {@code map.sql} dump does <strong>not</strong> include field-type
 * information (the 15c / 9c / 6c marker on a tile). We infer likely croppers
 * from two cheap signals already present in the rows:
 * <ul>
 *   <li><strong>capital flag</strong> — a player's #1 priority cropper is their
 *       capital almost always. The dump emits {@code capital='1'} for the
 *       capital tile.</li>
 *   <li><strong>population</strong> — for a capital, very high population early
 *       in a server is strong evidence of a 15c (you can't get to 1500+ pop in
 *       a non-cropper village at any reasonable pace).</li>
 *   <li><strong>player village count</strong> — players with 4+ villages who
 *       have a high-pop non-capital probably built a side 9c.</li>
 * </ul>
 *
 * <h3>Buckets</h3>
 * <ul>
 *   <li>{@code HIGH_15C}    — capital AND pop &gt; 1500</li>
 *   <li>{@code LIKELY_15C}  — capital AND pop &gt; 700</li>
 *   <li>{@code POSSIBLE_15C} — capital AND any pop</li>
 *   <li>{@code LIKELY_9C}   — non-capital AND pop &gt; 1000 AND player has &ge; 4 villages</li>
 *   <li>{@code UNKNOWN}     — anything else</li>
 * </ul>
 *
 * <h3>Limitation — empty croppers are invisible here</h3>
 * Uninhabited tiles do not appear in {@code map.sql} at all (Travian only lists
 * villages that exist). This classifier therefore cannot identify
 * <em>unoccupied</em> 15c / 9c spots — only currently-settled ones. Discovering
 * empty croppers requires a per-tile scrape (slow, polite rate-limiting
 * required) and is deferred to a later milestone. See
 * {@code CropperFinderService} for the user-facing TODO note.
 */
@Service
@Transactional
public class CropperClassifierService {

    private static final Logger log = LoggerFactory.getLogger(CropperClassifierService.class);

    public static final String HIGH_15C = "HIGH_15C";
    public static final String LIKELY_15C = "LIKELY_15C";
    public static final String POSSIBLE_15C = "POSSIBLE_15C";
    public static final String LIKELY_9C = "LIKELY_9C";
    public static final String UNKNOWN = "UNKNOWN";

    /** Above-this population on a capital pushes the bucket up to HIGH_15C. */
    private static final int HIGH_15C_POP_THRESHOLD = 1500;
    /** Above-this population on a capital is the LIKELY_15C tier. */
    private static final int LIKELY_15C_POP_THRESHOLD = 700;
    /** Non-capital pop threshold for the LIKELY_9C bucket. */
    private static final int LIKELY_9C_POP_THRESHOLD = 1000;
    /** Min total village count for the LIKELY_9C "side cropper" heuristic. */
    private static final int LIKELY_9C_MIN_VILLAGE_COUNT = 4;

    private final MapVillageRepository villageRepository;
    private final MapPlayerRepository playerRepository;

    public CropperClassifierService(
            MapVillageRepository villageRepository,
            MapPlayerRepository playerRepository
    ) {
        this.villageRepository = villageRepository;
        this.playerRepository = playerRepository;
    }

    /**
     * Run the heuristic across every {@link MapVillage} for this server and
     * stamp the bucket onto each row. Returns the count of villages classified
     * as anything other than {@code UNKNOWN} (i.e. "interesting" candidates).
     * <p>
     * Idempotent — re-running just overwrites the previous classification with
     * the same value when nothing changed.
     */
    public int classifyServer(Long serverId) {
        if (serverId == null) throw new IllegalArgumentException("serverId is required");

        Specification<MapPlayer> serverSpec = (root, q, cb) ->
                cb.equal(root.get("serverId"), serverId);
        List<MapPlayer> players = playerRepository.findAll(serverSpec);
        Map<Long, Integer> villageCountByUid = new HashMap<>();
        for (MapPlayer p : players) {
            villageCountByUid.put(p.getTravianUid(),
                    p.getVillageCount() == null ? 0 : p.getVillageCount());
        }

        List<MapVillage> villages = villageRepository.findAllByServerId(serverId);

        int interesting = 0;
        for (MapVillage v : villages) {
            int playerVillages = villageCountByUid.getOrDefault(v.getPlayerUid(), 0);
            String bucket = classify(v, playerVillages);
            v.setCropperClassification(bucket);
            villageRepository.save(v);
            if (!UNKNOWN.equals(bucket)) interesting++;
        }

        log.info("cropper classify for server {}: {} villages scanned, {} non-UNKNOWN",
                serverId, villages.size(), interesting);
        return interesting;
    }

    /**
     * Pure-function classifier exposed package-private for unit testing. Order
     * of checks matters — HIGH_15C is the strongest signal so it wins first.
     */
    String classify(MapVillage v, int playerVillageCount) {
        boolean capital = Boolean.TRUE.equals(v.getIsCapital());
        int pop = v.getPopulation() == null ? 0 : v.getPopulation();

        if (capital && pop > HIGH_15C_POP_THRESHOLD) return HIGH_15C;
        if (capital && pop > LIKELY_15C_POP_THRESHOLD) return LIKELY_15C;
        if (capital) return POSSIBLE_15C;
        if (pop > LIKELY_9C_POP_THRESHOLD && playerVillageCount >= LIKELY_9C_MIN_VILLAGE_COUNT) {
            return LIKELY_9C;
        }
        return UNKNOWN;
    }
}
