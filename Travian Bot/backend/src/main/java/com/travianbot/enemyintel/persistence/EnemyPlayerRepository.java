package com.travianbot.enemyintel.persistence;

import com.travianbot.enemyintel.domain.EnemyPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA store for {@link EnemyPlayer}. Methods are deliberately narrow — the
 * scheduler composes them with explicit {@code Duration} math instead of
 * pushing freshness-window logic into the query layer.
 */
@Repository
public interface EnemyPlayerRepository extends JpaRepository<EnemyPlayer, Long> {

    /** Resolve the row scoped to (accountId, travianUid). */
    Optional<EnemyPlayer> findFirstByAccountIdAndTravianUid(Long accountId, Long travianUid);

    /** All enemies on this account, most-recently-scraped first. UI feed. */
    List<EnemyPlayer> findAllByAccountIdOrderByLastScrapedAtDesc(Long accountId);

    /**
     * Enemies whose last successful scrape is older than {@code cutoff} —
     * ordered ascending so the oldest gets picked first.
     */
    List<EnemyPlayer> findAllByAccountIdAndLastScrapedAtBeforeOrderByLastScrapedAtAsc(
            Long accountId, Instant cutoff);

    /** Account-deletion cleanup. */
    void deleteAllByAccountId(Long accountId);
}
