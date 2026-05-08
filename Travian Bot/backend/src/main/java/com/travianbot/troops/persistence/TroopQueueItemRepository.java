package com.travianbot.troops.persistence;

import com.travianbot.troops.domain.TroopBuilding;
import com.travianbot.troops.domain.TroopQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TroopQueueItemRepository extends JpaRepository<TroopQueueItem, Long> {

    List<TroopQueueItem> findAllByVillageIdOrderByOrderIndexAscIdAsc(Long villageId);

    List<TroopQueueItem> findAllByVillageIdAndBuildingOrderByOrderIndexAscIdAsc(
            Long villageId, TroopBuilding building);

    @Transactional
    void deleteByVillageId(Long villageId);
}
