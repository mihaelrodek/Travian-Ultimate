package com.travianbot.intel.persistence;

import com.travianbot.intel.domain.MapVillage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MapVillageRepository extends JpaRepository<MapVillage, Long> {

    /** Upsert pivot for a single village observation. */
    Optional<MapVillage> findFirstByServerIdAndTravianVid(Long serverId, Long travianVid);

    /** All villages belonging to one player on this server's map. */
    List<MapVillage> findAllByServerIdAndPlayerUid(Long serverId, Long playerUid);

    /**
     * Every village this server has observed. Used by the cropper classifier
     * for the full-server refresh pass — avoids the
     * {@link #findAllByServerIdAndXBetweenAndYBetween} workaround of passing
     * {@code Integer.MIN_VALUE/MAX_VALUE} when no spatial filter is needed.
     */
    List<MapVillage> findAllByServerId(Long serverId);

    /**
     * Axis-aligned bounding-box query used as the cheap pre-filter for the
     * distance-from-pivot search. The result is post-filtered by Euclidean
     * distance in the service layer.
     */
    List<MapVillage> findAllByServerIdAndXBetweenAndYBetween(
            Long serverId, int xMin, int xMax, int yMin, int yMax);

    /** Cascade hook — called by {@code ServerService.delete} to wipe the server's tracked villages. */
    void deleteAllByServerId(Long serverId);
}
