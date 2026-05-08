package com.travianbot.settings.persistence;

import com.travianbot.settings.domain.GeneralSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneralSettingsRepository extends JpaRepository<GeneralSettings, Long> {
}
