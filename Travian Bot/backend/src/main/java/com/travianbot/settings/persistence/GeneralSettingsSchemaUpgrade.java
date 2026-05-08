package com.travianbot.settings.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent schema cleanup for the {@code general_settings} singleton.
 * <p>
 * Background: {@code village_scrape_interval_minutes} used to live here as a
 * single global knob. We moved it to {@link com.travianbot.village.domain.VillageSettings#getAutoScrapeIntervalMinutes()}
 * because a global interval doesn't fit real play — a capital under attack wants
 * 2–5 min polling while a quiet support village is fine at 30–60.
 * <p>
 * Hibernate's {@code ddl-auto: update} adds columns but never removes them, so
 * the legacy column lingers as a not-null field with no Java mapping. Any future
 * {@code INSERT} (e.g. fresh install cloning from an existing row) would fail on
 * the orphaned NOT NULL. {@code DROP COLUMN IF EXISTS} is idempotent and safe
 * to run on every boot.
 */
@Component
public class GeneralSettingsSchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(GeneralSettingsSchemaUpgrade.class);

    private final JdbcTemplate jdbcTemplate;

    public GeneralSettingsSchemaUpgrade(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        dropIfExists("village_scrape_interval_minutes");
    }

    private void dropIfExists(String column) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE general_settings DROP COLUMN IF EXISTS " + column
            );
            log.debug("Dropped legacy column general_settings.{} (if it existed)", column);
        } catch (Exception e) {
            // WARN, not DEBUG — if the drop fails, the entity/table are out of
            // sync and future INSERTs will explode. Worth surfacing even though
            // the app will keep running because the column is unused.
            log.warn("Could not drop legacy column general_settings.{}: {}", column, e.getMessage());
        }
    }
}
