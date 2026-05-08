package com.travianbot.village.persistence;

import com.travianbot.village.domain.VillageSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VillageSettingsRepository extends JpaRepository<VillageSettings, Long> {
    Optional<VillageSettings> findByVillageId(Long villageId);
    void deleteByVillageId(Long villageId);
}
