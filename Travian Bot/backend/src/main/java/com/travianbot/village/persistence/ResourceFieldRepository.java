package com.travianbot.village.persistence;

import com.travianbot.village.domain.ResourceField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceFieldRepository extends JpaRepository<ResourceField, Long> {

    List<ResourceField> findAllByVillageIdOrderBySlotIdAsc(Long villageId);

    void deleteByVillageId(Long villageId);
}
