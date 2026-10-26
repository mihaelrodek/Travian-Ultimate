package com.travianbot.troops.persistence;

import com.travianbot.troops.domain.VillageTroopCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA store for {@link VillageTroopCount}. The unique-on-village_id
 * constraint enforces "one snapshot per village" — the service upserts via
 * {@link #findFirstByVillageId(Long)} + save rather than relying on
 * persistence-level merge semantics.
 */
@Repository
public interface VillageTroopCountRepository extends JpaRepository<VillageTroopCount, Long> {

    /** Every snapshot for the account, used by the list endpoint. */
    List<VillageTroopCount> findAllByAccountId(Long accountId);

    /** Upsert lookup — one snapshot per village. */
    Optional<VillageTroopCount> findFirstByVillageId(Long villageId);

    /** Account-delete cleanup hook. */
    void deleteAllByAccountId(Long accountId);
}
