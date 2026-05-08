package com.travianbot.village.persistence;

import com.travianbot.village.domain.BuildingQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildingQueueItemRepository extends JpaRepository<BuildingQueueItem, Long> {
    List<BuildingQueueItem> findAllByVillageIdOrderByOrderIndexAscIdAsc(Long villageId);
}
