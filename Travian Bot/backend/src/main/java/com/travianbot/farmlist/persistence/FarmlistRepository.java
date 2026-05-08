package com.travianbot.farmlist.persistence;

import com.travianbot.farmlist.domain.Farmlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FarmlistRepository extends JpaRepository<Farmlist, Long> {
    List<Farmlist> findAllByOrderByCreatedAtAsc();
    List<Farmlist> findAllByAccountIdOrderByCreatedAtAsc(Long accountId);

    /** Enabled lists that have an interval set — candidates for the scheduler. */
    List<Farmlist> findAllByEnabledTrueAndSendIntervalMinutesIsNotNull();

    /** Used by the scraper to reconcile by Travian id. */
    Optional<Farmlist> findFirstByAccountIdAndTravianListId(Long accountId, Long travianListId);

    /** Fallback when we haven't discovered the Travian id yet. */
    Optional<Farmlist> findFirstByAccountIdAndName(Long accountId, String name);

    /** All lists in the same group on the same account — used for group-override in the scheduler. */
    List<Farmlist> findAllByAccountIdAndGroupTag(Long accountId, String groupTag);

    void deleteAllByAccountId(Long accountId);
}
