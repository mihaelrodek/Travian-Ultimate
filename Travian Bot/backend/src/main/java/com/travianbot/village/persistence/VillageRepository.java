package com.travianbot.village.persistence;

import com.travianbot.village.domain.Village;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VillageRepository extends JpaRepository<Village, Long> {
    /**
     * Primary ordering for the Villages sidebar: user-chosen {@code orderIndex}
     * first, falling back to creation time so rows that were never manually
     * reordered still appear in a stable, deterministic order.
     */
    List<Village> findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(Long accountId);

    void deleteAllByAccountId(Long accountId);

    Optional<Village> findFirstByAccountIdAndTravianVillageId(Long accountId, Long travianVillageId);
    Optional<Village> findFirstByAccountIdAndCoordinateXAndCoordinateY(Long accountId, int x, int y);
}
