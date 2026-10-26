package com.travianbot.farmlist.persistence;

import com.travianbot.farmlist.domain.RaidReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RaidReportRepository extends JpaRepository<RaidReport, Long> {

    /** Dedup probe — the scraper skips inserting if this returns present. */
    Optional<RaidReport> findFirstByAccountIdAndTravianReportId(Long accountId, Long travianReportId);

    /** History of one target, latest report first. */
    List<RaidReport> findAllByFarmlistTargetIdOrderByRaidReturnedAtDesc(Long farmlistTargetId);

    /** Recent reports across an account — used for the "last hour" view. */
    List<RaidReport> findAllByAccountIdAndRaidReturnedAtAfter(Long accountId, Instant cutoff);

    /** Used to bound the analytics summary to a single farmlist. */
    List<RaidReport> findAllByFarmlistIdOrderByRaidReturnedAtDesc(Long farmlistId);
}
