package com.travianbot.village.persistence;

import com.travianbot.village.domain.VillageTrainConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface VillageTrainConfigRepository extends JpaRepository<VillageTrainConfig, Long> {

    List<VillageTrainConfig> findAllByVillageIdOrderByTroopCodeAsc(Long villageId);

    @Modifying
    @Transactional
    @Query("delete from VillageTrainConfig c where c.village.id = ?1")
    void deleteByVillageId(Long villageId);
}
