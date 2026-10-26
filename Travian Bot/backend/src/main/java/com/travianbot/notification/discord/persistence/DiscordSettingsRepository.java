package com.travianbot.notification.discord.persistence;

import com.travianbot.notification.discord.domain.DiscordSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Single-row store. We never look up by anything other than
 * {@link DiscordSettings#SINGLETON_ID} = 1 — the service layer uses
 * {@link JpaRepository#findById(Object)} + {@code orElseGet(new...)} to
 * lazily materialise the row on first access.
 */
@Repository
public interface DiscordSettingsRepository extends JpaRepository<DiscordSettings, Long> {
}
