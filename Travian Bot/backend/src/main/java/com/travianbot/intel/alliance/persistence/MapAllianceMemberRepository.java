package com.travianbot.intel.alliance.persistence;

import com.travianbot.intel.alliance.domain.MapAllianceMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface MapAllianceMemberRepository extends JpaRepository<MapAllianceMember, Long> {

    /** Roster for one alliance, biggest player first. */
    List<MapAllianceMember> findAllByMapAllianceIdOrderByPopulationDesc(Long mapAllianceId);

    /** Used before re-writing the roster from a fresh detail scrape. */
    void deleteAllByMapAllianceId(Long mapAllianceId);

    /**
     * Bulk delete by parent alliance ids — called from {@code ServerService.delete}
     * to wipe an entire server's rosters in one shot. Empty collection is a no-op.
     */
    void deleteAllByMapAllianceIdIn(Collection<Long> mapAllianceIds);
}
