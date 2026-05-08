package com.travianbot.statistics.persistence;

import com.travianbot.statistics.domain.StatCategory;
import com.travianbot.statistics.domain.StatSnapshot;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StatSnapshotRepository extends JpaRepository<StatSnapshot, Long> {

    /** Most recent snapshot for the account+category — the "now" row in the UI. */
    Optional<StatSnapshot> findFirstByAccountIdAndCategoryOrderByCapturedAtDesc(
            Long accountId, StatCategory category);

    /**
     * Two most-recent snapshots for a category — the UI renders delta = latest - previous.
     * Returns 0, 1, or 2 rows; caller handles the less-than-2 case.
     */
    List<StatSnapshot> findAllByAccountIdAndCategoryOrderByCapturedAtDesc(
            Long accountId, StatCategory category, Limit limit);

    /**
     * Full history window for a category. Caller passes {@code since} from
     * {@code Instant.now().minus(hours)} and we return ascending by time so the
     * chart can plot straight through without reversing.
     */
    List<StatSnapshot> findAllByAccountIdAndCategoryAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(
            Long accountId, StatCategory category, Instant since);

    void deleteAllByAccountId(Long accountId);
}
