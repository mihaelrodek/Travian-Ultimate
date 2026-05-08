package com.travianbot.village.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup repair for newly-added {@code village_settings} columns + seed for any
 * village missing a settings row.
 *
 * <h3>Why this bean has to exist</h3>
 * Hibernate's {@code ddl-auto=update} emits {@code ALTER TABLE ADD COLUMN …} for
 * new entity fields, but when the field is annotated {@code nullable=false} and
 * the table already has rows, Postgres rejects the ADD with "column … contains
 * null values". Hibernate logs the failure and moves on — the column never
 * lands, and every subsequent SELECT that references it 500s with error 42703.
 * <p>
 * We mirror the pattern used by {@code VillageSchemaUpgrade} /
 * {@code AccountSchemaUpgrade}: right after Hibernate's own schema pass (i.e.
 * on {@link ApplicationReadyEvent}) we re-issue each ADD COLUMN ourselves, but
 * as a nullable column with an explicit DEFAULT, then flip it to NOT NULL.
 * Hibernate's earlier failed attempt on this boot was a no-op at the SQL
 * level; on the next boot Hibernate sees the column and moves on.
 * <p>
 * Idempotent — every step checks {@code information_schema}, so re-running on
 * a clean database is a no-op. Leave the bean in place as a living migration
 * log.
 */
@Component
public class VillageSettingsSchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(VillageSettingsSchemaUpgrade.class);

    private final JdbcTemplate jdbcTemplate;

    public VillageSettingsSchemaUpgrade(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        // Ensure every NOT NULL column from the entity actually exists on the
        // table. Must run BEFORE the backfill / seed below, which reference the
        // columns in their SQL.
        ensureAutoScrapeIntervalColumn();
        ensureCropWatcherColumns();
        ensureTrainTroopsColumns();

        // Backfill defensively in case the ADD COLUMN above created the column
        // nullable for a split second (it shouldn't — we set DEFAULT up-front —
        // but cheap belt-and-brace).
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE village_settings SET auto_scrape_interval_minutes = 15 "
                            + "WHERE auto_scrape_interval_minutes IS NULL OR auto_scrape_interval_minutes <= 0"
            );
            if (updated > 0) {
                log.info("Backfilled auto_scrape_interval_minutes to 15 for {} existing village_settings row(s)", updated);
            }
        } catch (Exception e) {
            log.debug("Skipped auto_scrape_interval_minutes backfill: {}", e.getMessage());
        }

        try {
            int inserted = jdbcTemplate.update(
                    "INSERT INTO village_settings ("
                            + "village_id, npc_enabled, npc_trigger_pct, "
                            + "npc_ratio_wood, npc_ratio_clay, npc_ratio_iron, npc_ratio_crop, "
                            + "gold_reserve, auto_scrape_enabled, auto_scrape_interval_minutes, "
                            + "crop_watcher_enabled, crop_watcher_threshold_pct, crop_watcher_min_gold, "
                            + "train_troops_enabled, train_troops_min_minutes, train_troops_max_minutes) "
                            + "SELECT v.id, FALSE, 95, 25, 25, 25, 25, 10, TRUE, 15, "
                            + "FALSE, 20, 0, "
                            + "FALSE, 15, 60 "
                            + "FROM village v "
                            + "WHERE NOT EXISTS ("
                            + "    SELECT 1 FROM village_settings s WHERE s.village_id = v.id)"
            );
            if (inserted > 0) {
                log.info("Seeded village_settings rows for {} existing village(s)", inserted);
            }
        } catch (Exception e) {
            log.warn("Could not backfill village_settings rows: {} — "
                            + "if the village_settings table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    /**
     * Adds {@code village_settings.auto_scrape_interval_minutes} if Hibernate
     * couldn't (populated table + NOT NULL without DEFAULT is a DDL dead-end
     * for {@code ddl-auto=update}).
     * <p>
     * Strategy: ADD COLUMN nullable with DEFAULT 15 (Postgres fills existing
     * rows), then explicitly UPDATE nulls (belt-and-brace — DEFAULT already
     * covered it on ADD COLUMN for Postgres ≥ 11), then flip to NOT NULL to
     * match the entity mapping.
     */
    private void ensureAutoScrapeIntervalColumn() {
        try {
            if (!tableExists("village_settings")) {
                // Brand-new database — Hibernate will create the table with
                // every column in one shot. Nothing to repair.
                return;
            }
            if (columnExists("village_settings", "auto_scrape_interval_minutes")) {
                return;
            }
            // Nullable + DEFAULT makes ADD COLUMN safe against populated tables.
            jdbcTemplate.execute(
                    "ALTER TABLE village_settings ADD COLUMN auto_scrape_interval_minutes INTEGER DEFAULT 15");
            jdbcTemplate.update(
                    "UPDATE village_settings SET auto_scrape_interval_minutes = 15 "
                            + "WHERE auto_scrape_interval_minutes IS NULL");
            jdbcTemplate.execute(
                    "ALTER TABLE village_settings ALTER COLUMN auto_scrape_interval_minutes SET NOT NULL");
            log.info("Added village_settings.auto_scrape_interval_minutes column (default 15, NOT NULL)");
        } catch (Exception e) {
            // WARN, not DEBUG — if this silently fails the Village Settings tab
            // will 500 on every load. Loud log > silent misery.
            log.warn("Could not ensure auto_scrape_interval_minutes column on village_settings: {} — "
                            + "if the village_settings table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    /**
     * Adds the three train-troops columns when upgrading. Default to disabled
     * + a 15..60 minute window — sane "I'm not running this yet" baseline.
     */
    private void ensureTrainTroopsColumns() {
        try {
            if (!tableExists("village_settings")) return;
            ensureBoolColumn("village_settings", "train_troops_enabled", false);
            ensureIntColumn("village_settings", "train_troops_min_minutes", 15);
            ensureIntColumn("village_settings", "train_troops_max_minutes", 60);
        } catch (Exception e) {
            log.warn("Could not ensure train_troops_* columns on village_settings: {} — "
                            + "if the village_settings table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    /**
     * Adds the three crop-watcher columns when an existing database is upgraded
     * to a build that includes them. Same nullable-with-DEFAULT-then-NOT-NULL
     * dance as {@link #ensureAutoScrapeIntervalColumn()} so populated tables
     * survive the migration.
     */
    private void ensureCropWatcherColumns() {
        try {
            if (!tableExists("village_settings")) {
                return;
            }
            ensureBoolColumn("village_settings", "crop_watcher_enabled", false);
            ensureIntColumn("village_settings", "crop_watcher_threshold_pct", 20);
            ensureIntColumn("village_settings", "crop_watcher_min_gold", 0);
        } catch (Exception e) {
            log.warn("Could not ensure crop_watcher_* columns on village_settings: {} — "
                            + "if the village_settings table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    private void ensureBoolColumn(String table, String column, boolean defaultValue) {
        if (columnExists(table, column)) return;
        String def = defaultValue ? "TRUE" : "FALSE";
        jdbcTemplate.execute(
                "ALTER TABLE " + table + " ADD COLUMN " + column + " BOOLEAN DEFAULT " + def);
        jdbcTemplate.update(
                "UPDATE " + table + " SET " + column + " = " + def + " WHERE " + column + " IS NULL");
        jdbcTemplate.execute(
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
        log.info("Added {}.{} column (default {}, NOT NULL)", table, column, def);
    }

    private void ensureIntColumn(String table, String column, int defaultValue) {
        if (columnExists(table, column)) return;
        jdbcTemplate.execute(
                "ALTER TABLE " + table + " ADD COLUMN " + column + " INTEGER DEFAULT " + defaultValue);
        jdbcTemplate.update(
                "UPDATE " + table + " SET " + column + " = " + defaultValue + " WHERE " + column + " IS NULL");
        jdbcTemplate.execute(
                "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
        log.info("Added {}.{} column (default {}, NOT NULL)", table, column, defaultValue);
    }

    private boolean tableExists(String table) {
        Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, table);
        return hits != null && hits > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return hits != null && hits > 0;
    }
}
