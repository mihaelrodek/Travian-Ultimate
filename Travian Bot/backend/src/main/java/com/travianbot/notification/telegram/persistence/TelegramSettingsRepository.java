package com.travianbot.notification.telegram.persistence;

import com.travianbot.notification.telegram.domain.TelegramSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Single-row store for {@link TelegramSettings}; the service materialises the
 * id=1 row lazily on first access.
 */
@Repository
public interface TelegramSettingsRepository extends JpaRepository<TelegramSettings, Long> {
}
