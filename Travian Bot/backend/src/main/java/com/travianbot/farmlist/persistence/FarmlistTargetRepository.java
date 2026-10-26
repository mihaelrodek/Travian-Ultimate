package com.travianbot.farmlist.persistence;

import com.travianbot.farmlist.domain.FarmlistTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Direct access to individual farmlist targets. Most reads/writes go through
 * {@link com.travianbot.farmlist.domain.Farmlist#getTargets()} via the
 * cascading {@code @OneToMany}, but the raid-report analyzer needs to attribute
 * incoming reports back to a target by coordinate, which is fastest as a
 * direct query rather than walking every list's targets.
 */
public interface FarmlistTargetRepository extends JpaRepository<FarmlistTarget, Long> {

    /**
     * Attribution lookup: incoming raid reports carry only coords, so to map
     * them back to the target that launched them we look up the target by
     * (account, x, y). When the same coords appear in multiple lists for the
     * same account we take the first hit — the analyzer is intentionally a
     * best-effort attributor, not a billing system.
     */
    Optional<FarmlistTarget> findFirstByFarmlistAccountIdAndCoordinateXAndCoordinateY(
            Long accountId, int coordinateX, int coordinateY);

    /** All targets that belong to the given farmlist. Used by the analytics endpoint. */
    List<FarmlistTarget> findAllByFarmlistId(Long farmlistId);
}
