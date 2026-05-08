package com.travianbot.statistics.persistence;

import com.travianbot.statistics.domain.StatCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * One-shot cleanup that runs on application start. The {@link StatCategory}
 * enum was redefined in Phase H (POPULATION/OFFENSIVE/DEFENSIVE/HEROES →
 * PVP/PVE/DEFENSE/RAIDERS). Rows carrying the old enum strings would make
 * Hibernate throw {@code IllegalArgumentException} when it tries to
 * deserialize them, and because the read path is cross-cutting (event
 * listeners, schedulers, the top10 endpoint) a single bad row would break
 * the whole statistics feature.
 * <p>
 * We also have to rewrite the Postgres CHECK constraint that Hibernate
 * generated the first time the table was created — {@code ddl-auto=update}
 * does <em>not</em> refresh an existing CHECK constraint when the
 * {@link jakarta.persistence.Enumerated} values change. Without this fix
 * INSERTing a row with a new enum value (e.g. PVP) fails with
 * {@code stat_snapshot_category_check} even though the column is otherwise
 * happy with it. The bean drops the old constraint and re-adds it with the
 * current enum whitelist.
 * <p>
 * Idempotent: every DDL statement is conditional, and the deletes are no-ops
 * on clean databases. Safe to leave in place forever; it's a few
 * milliseconds at boot.
 */
@Component
public class StatCategoryLegacyCleanup {

    private static final Logger log = LoggerFactory.getLogger(StatCategoryLegacyCleanup.class);

    private final JdbcTemplate jdbcTemplate;

    public StatCategoryLegacyCleanup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wipeLegacyCategories() {
        // Build the whitelist from the enum itself so adding a new value later
        // automatically broadens the "valid" set without a code change here.
        String validCsv = Arrays.stream(StatCategory.values())
                .map(c -> "'" + c.name() + "'")
                .collect(Collectors.joining(", "));

        // Step 1 — refresh the CHECK constraint FIRST. This is the part that
        // actually unblocks inserts of the new enum values; deleting old rows
        // is cosmetic. We run it before the deletes so that even if the
        // deletes throw (e.g. tables don't exist yet on a clean install)
        // we've still fixed the constraint.
        //
        // A separate try/catch per step means a failure in one doesn't mask
        // the other — previously a silent DEBUG-level swallow was the
        // reason this could look fixed but not actually run.
        rebuildCategoryCheckConstraint(validCsv);

        // Step 2 — remove leftover rows with the old enum strings. The read
        // path would throw IllegalArgumentException on them.
        wipeStaleSnapshotRows(validCsv);
    }

    private void rebuildCategoryCheckConstraint(String validCsv) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE stat_snapshot DROP CONSTRAINT IF EXISTS stat_snapshot_category_check");
            jdbcTemplate.execute(
                    "ALTER TABLE stat_snapshot ADD CONSTRAINT stat_snapshot_category_check "
                            + "CHECK (category IN (" + validCsv + "))");
            log.info("stat_snapshot_category_check refreshed to allow {}", validCsv);
        } catch (Exception e) {
            // Surfaced at WARN (not DEBUG!) because if this silently fails
            // every INSERT from StatisticsScrapingService will 500. Better
            // to make the root cause visible than to let the symptom
            // re-appear a minute later.
            log.warn("Failed to refresh stat_snapshot_category_check: {} — "
                    + "if the stat_snapshot table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    private void wipeStaleSnapshotRows(String validCsv) {
        try {
            int entries = jdbcTemplate.update(
                    "DELETE FROM stat_top10_entry WHERE snapshot_id IN ("
                            + "SELECT id FROM stat_snapshot WHERE category NOT IN (" + validCsv + "))");
            int snaps = jdbcTemplate.update(
                    "DELETE FROM stat_snapshot WHERE category NOT IN (" + validCsv + ")");
            if (snaps > 0 || entries > 0) {
                log.info("Cleaned up legacy statistics rows: {} snapshot(s), {} entry row(s)",
                        snaps, entries);
            }
        } catch (Exception e) {
            log.debug("Legacy stats row wipe skipped: {}", e.getMessage());
        }
    }
}
