package com.travianbot.intel.persistence;

import com.travianbot.intel.domain.MapVillagePopulationSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MapVillagePopulationSnapshotRepository
        extends JpaRepository<MapVillagePopulationSnapshot, Long> {

    /** Most recent first, capped by Pageable so callers can ask for "last N days". */
    @Query("SELECT s FROM MapVillagePopulationSnapshot s "
            + "WHERE s.mapVillageId = ?1 ORDER BY s.capturedAt DESC")
    List<MapVillagePopulationSnapshot> findRecentForVillage(Long mapVillageId, Pageable pageable);

    /**
     * Get the absolute most-recent snapshot to dedupe against in the sync —
     * if the new population matches this row's, we skip writing a new one.
     */
    @Query("SELECT s FROM MapVillagePopulationSnapshot s "
            + "WHERE s.mapVillageId = ?1 ORDER BY s.capturedAt DESC LIMIT 1")
    MapVillagePopulationSnapshot findLatestForVillage(Long mapVillageId);

    /** Bulk fetch used by the chart endpoint over a date window. */
    List<MapVillagePopulationSnapshot> findAllByMapVillageIdAndCapturedAtAfterOrderByCapturedAtAsc(
            Long mapVillageId, Instant cutoff);

    /** Cascade hook from {@code ServerService.delete} → wipes orphaned snapshots. */
    void deleteAllByMapVillageIdIn(java.util.Collection<Long> mapVillageIds);
}
