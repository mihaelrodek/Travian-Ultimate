package com.travianbot.village.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Startup repair for newly-added {@code village} columns. Hibernate's
 * {@code ddl-auto=update} cannot add a NOT NULL column to a populated table —
 * it emits the ADD COLUMN but Postgres rejects it with
 * {@code column ... contains null values} because existing rows would be in
 * immediate breach. That leaves the table without the new column and every
 * subsequent query that selects it 500s.
 * <p>
 * We mirror the pattern used by {@code AccountSchemaUpgrade}: on
 * {@link ApplicationReadyEvent} — i.e. right after Hibernate's own schema pass
 * — we re-try each ADD COLUMN ourselves, but as a nullable column with an
 * explicit DEFAULT, then flip the constraint to NOT NULL. Hibernate's failed
 * attempt on this boot is harmless (it was a no-op at the SQL level), and on
 * the next boot Hibernate sees the column already exists and moves on.
 * <p>
 * Idempotent — every step checks {@code information_schema}, so re-running on
 * a clean database is a no-op. Leave the bean in place as a living migration
 * log.
 */
@Component
public class VillageSchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(VillageSchemaUpgrade.class);

    private final JdbcTemplate jdbcTemplate;

    public VillageSchemaUpgrade(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        ensureOrderIndexColumn();
    }

    /**
     * Adds {@code village.order_index} if Hibernate couldn't. We add it as
     * nullable with DEFAULT 0, so Postgres doesn't complain about existing
     * rows, then backfill explicitly (belt-and-brace — DEFAULT already handles
     * it for ADD COLUMN) and flip the column to NOT NULL to match the entity
     * mapping.
     * <p>
     * If the column was already there (from a fresh install that avoided the
     * Hibernate failure, or a repeat boot after this bean already ran) the
     * existence check short-circuits and nothing happens.
     */
    private void ensureOrderIndexColumn() {
        try {
            if (columnExists("village", "order_index")) {
                return;
            }
            // Nullable + DEFAULT lets the ADD COLUMN succeed against populated tables.
            jdbcTemplate.execute(
                    "ALTER TABLE village ADD COLUMN order_index INTEGER DEFAULT 0");
            // Belt-and-brace: DEFAULT covers new rows, but explicit UPDATE is cheap
            // and makes the migration auditable ("which rows did we touch?").
            jdbcTemplate.update(
                    "UPDATE village SET order_index = 0 WHERE order_index IS NULL");
            jdbcTemplate.execute(
                    "ALTER TABLE village ALTER COLUMN order_index SET NOT NULL");
            log.info("Added village.order_index column (default 0, NOT NULL)");
        } catch (Exception e) {
            // WARN, not DEBUG — if this silently fails the Villages page will 500
            // on every load. Loud log > silent misery.
            log.warn("Could not ensure order_index column on village table: {} — "
                    + "if the village table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        Integer hits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return hits != null && hits > 0;
    }
}
