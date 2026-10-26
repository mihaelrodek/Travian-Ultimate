package com.travianbot.intel.persistence;

import com.travianbot.intel.domain.MapPlayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface MapPlayerRepository
        extends JpaRepository<MapPlayer, Long>, JpaSpecificationExecutor<MapPlayer> {

    /** Lookup the row scoped to a single tracked server + in-game uid. */
    Optional<MapPlayer> findFirstByServerIdAndTravianUid(Long serverId, Long travianUid);

    /** All players on this server currently considered inactive — used by farmlist auto-suggest. */
    List<MapPlayer> findAllByServerIdAndIsInactiveTrue(Long serverId);

    /** Bulk load — used by the sync to build an in-memory uid → MapPlayer map. */
    List<MapPlayer> findAllByServerId(Long serverId);

    /** Cascade hook — called by {@code ServerService.delete} to wipe the server's tracked players. */
    void deleteAllByServerId(Long serverId);
}
