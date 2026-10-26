package com.travianbot.intel.alliance.persistence;

import com.travianbot.intel.alliance.domain.MapAlliance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface MapAllianceRepository
        extends JpaRepository<MapAlliance, Long>, JpaSpecificationExecutor<MapAlliance> {

    /** Lookup scoped to a single tracked server + in-game alliance id. */
    Optional<MapAlliance> findFirstByServerIdAndTravianAid(Long serverId, Long travianAid);

    /** All alliances on a server, biggest first — used by the picker/list view. */
    List<MapAlliance> findAllByServerIdOrderByMemberCountDesc(Long serverId);

    /** Cascade hook — called by {@code ServerService.delete}. */
    void deleteAllByServerId(Long serverId);

    /** Used for "select by name" autocomplete lookups; case-insensitive. */
    Optional<MapAlliance> findFirstByServerIdAndNameIgnoreCase(Long serverId, String name);
}
