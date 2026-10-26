package com.travianbot.incoming.persistence;

import com.travianbot.incoming.domain.IncomingAttack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA store for {@link IncomingAttack}. Methods are intentionally narrow —
 * the service composes them with explicit recomputes instead of pushing
 * business logic into the repository.
 */
@Repository
public interface IncomingAttackRepository extends JpaRepository<IncomingAttack, Long> {

    /** Every incoming on this account, soonest arrival first. */
    List<IncomingAttack> findAllByAccountIdOrderByArrivalAtAsc(Long accountId);

    /**
     * Lookup keyed exactly the same way as the unique constraint. Used by
     * the scraper to decide whether a freshly-parsed wave is new or a
     * re-scrape of one we've already stored.
     */
    Optional<IncomingAttack> findFirstByAccountIdAndTargetVillageIdAndArrivalAtAndSourceXAndSourceY(
            Long accountId,
            Long targetVillageId,
            Instant arrivalAt,
            Integer sourceX,
            Integer sourceY
    );

    /** Future-arrival attacks on this account — feeds the planner. */
    List<IncomingAttack> findAllByAccountIdAndArrivalAtAfter(Long accountId, Instant cutoff);

    /** Cleanup for the account-deletion listener. */
    void deleteAllByAccountId(Long accountId);
}
