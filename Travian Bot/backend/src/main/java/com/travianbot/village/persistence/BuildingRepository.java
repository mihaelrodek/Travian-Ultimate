package com.travianbot.village.persistence;

import com.travianbot.village.domain.Building;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    List<Building> findAllByVillageIdOrderBySlotIdAsc(Long villageId);

    void deleteByVillageId(Long villageId);
}
