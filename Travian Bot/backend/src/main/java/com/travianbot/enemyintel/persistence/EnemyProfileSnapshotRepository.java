package com.travianbot.enemyintel.persistence;

import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA store for {@link EnemyProfileSnapshot} (append-only profile history).
 */
@Repository
public interface EnemyProfileSnapshotRepository
        extends JpaRepository<EnemyProfileSnapshot, Long> {

    /** Full history for one enemy, most-recent first. */
    List<EnemyProfileSnapshot> findAllByEnemyPlayerIdOrderByCapturedAtDesc(Long enemyPlayerId);

    /** Most recent 20 — the UI detail panel's timeline. */
    List<EnemyProfileSnapshot> findTop20ByEnemyPlayerIdOrderByCapturedAtDesc(Long enemyPlayerId);

    /** Most recent snapshot only — used by the scraper to compute the diff against. */
    Optional<EnemyProfileSnapshot> findFirstByEnemyPlayerIdOrderByCapturedAtDesc(Long enemyPlayerId);

    /**
     * Bulk delete keyed by the parent {@link com.travianbot.enemyintel.domain.EnemyPlayer}
     * ids — feeds account-deletion cleanup, which first collects this account's
     * EnemyPlayer ids and then wipes their snapshot history.
     */
    void deleteAllByEnemyPlayerIdIn(Collection<Long> ids);
}
