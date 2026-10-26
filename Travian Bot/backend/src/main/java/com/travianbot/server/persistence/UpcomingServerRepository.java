package com.travianbot.server.persistence;

import com.travianbot.server.domain.UpcomingServer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link UpcomingServer}. Upsert is driven from the
 * service layer (find-by-calendar-id + save) rather than via JPQL, so a future
 * change to the lookup key shape (e.g. adding region into the natural key)
 * stays a one-place change.
 */
@Repository
public interface UpcomingServerRepository extends JpaRepository<UpcomingServer, Long> {

    /** Lookup by the synthetic stable id assembled in the scraper. */
    Optional<UpcomingServer> findFirstByCalendarId(String calendarId);

    /** Default ordering — earliest upcoming first. Nulls land at the end. */
    List<UpcomingServer> findAllByOrderByScheduledStartAtAsc();

    /** Filter view — used by the "upcoming launches" UI when a status filter is set. */
    List<UpcomingServer> findAllByStatusInOrderByScheduledStartAtAsc(Collection<String> statuses);
}
