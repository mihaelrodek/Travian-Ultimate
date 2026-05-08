package com.travianbot.account.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Startup repair for newly-added Account columns. Hibernate's {@code ddl-auto=update}
 * cannot add a NOT NULL column to a populated table — it emits the ADD COLUMN but
 * Postgres rejects it because the existing rows would instantly be in breach.
 * <p>
 * Each new column here adds the column as nullable with an explicit default, fills
 * existing rows, then flips the constraint to NOT NULL. Running this before
 * Hibernate has a chance to tighten the schema is what makes upgrades invisible to
 * the user.
 * <p>
 * Idempotent — each step checks via {@code information_schema}, so rerunning on a
 * clean database is a no-op. Leave the bean in place as a living migration log.
 */
@Component
public class AccountSchemaUpgrade {

    private static final Logger log = LoggerFactory.getLogger(AccountSchemaUpgrade.class);

    private final JdbcTemplate jdbcTemplate;

    public AccountSchemaUpgrade(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Single source of truth for "what columns does the current {@code AccountSettings}
     * entity define?". Everything else on the real table is legacy — sitter_*, oasis_*,
     * npc_*, schedule_* that were removed over time — and must be dropped before Hibernate
     * tries to INSERT a row that no longer names them. Otherwise Postgres rejects with
     * {@code null value in column ... violates not-null constraint}.
     * <p>
     * Active-window rows live in their own table (via {@code @OneToMany}), so they do NOT
     * belong here.
     */
    private static final Set<String> ACCOUNT_SETTINGS_CURRENT_COLUMNS = Set.of(
            "id",
            "account_id",
            "tribe",
            "hero_auto_level",
            "hero_heal_threshold_pct",
            "hero_auto_adventure",
            "hero_min_health_for_adventure",
            "alert_on_attack",
            "alert_include_troop_counts",
            "alert_channels",
            "proxy_url",
            "proxy_username",
            "proxy_password"
    );

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        ensurePlusAccountColumns();
        ensureNicknameColumn();
        dropOrphanedAccountSettingsColumns();
    }

    /**
     * Adds {@code account.nickname}. Same pattern as the other ensure*
     * methods: ADD COLUMN nullable first so Postgres doesn't reject against
     * pre-existing rows, backfill from {@code username} (our chosen default
     * when the user leaves the alias blank), then flip to NOT NULL so the
     * entity's constraint matches the live schema.
     */
    private void ensureNicknameColumn() {
        try {
            if (columnExists("account", "nickname")) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE account ADD COLUMN nickname VARCHAR(100)");
            jdbcTemplate.update("UPDATE account SET nickname = username WHERE nickname IS NULL");
            jdbcTemplate.execute("ALTER TABLE account ALTER COLUMN nickname SET NOT NULL");
            log.info("Added account.nickname column (backfilled from username, NOT NULL)");
        } catch (Exception e) {
            log.warn("Could not ensure account.nickname column: {} — "
                    + "if the account table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    /**
     * Drop every column on {@code account_settings} that is not in
     * {@link #ACCOUNT_SETTINGS_CURRENT_COLUMNS}. Hibernate's {@code ddl-auto=update}
     * never drops columns, so every time we remove a field from
     * {@link com.travianbot.account.domain.AccountSettings} the old column sticks
     * around — usually with a NOT NULL constraint and no server-side default. The
     * next INSERT (which no longer names the column) fails with
     * {@code null value in column "..." violates not-null constraint} and account
     * creation breaks.
     * <p>
     * Rather than maintaining a hand-written list of legacy columns (sitter_*,
     * oasis_*, npc_*, schedule_*, gold_reserve, ...), this method diffs the live
     * table against the keep-set and drops whatever is extra. Adding or removing a
     * field becomes a one-place change: update
     * {@link #ACCOUNT_SETTINGS_CURRENT_COLUMNS} when the entity changes and the
     * next boot reconciles the table.
     * <p>
     * Idempotent: on a clean install the keep-set matches the live columns
     * exactly and this is a no-op. If {@code account_settings} doesn't exist yet
     * (brand-new database, Hibernate hasn't created it) the lookup returns an
     * empty list and nothing happens.
     */
    private void dropOrphanedAccountSettingsColumns() {
        try {
            List<String> liveColumns = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = ? ORDER BY ordinal_position",
                    String.class, "account_settings");
            if (liveColumns.isEmpty()) {
                // Table not created yet — Hibernate will handle the initial
                // CREATE TABLE on first boot and there's nothing to reconcile.
                return;
            }
            for (String col : liveColumns) {
                if (ACCOUNT_SETTINGS_CURRENT_COLUMNS.contains(col)) {
                    continue;
                }
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE account_settings DROP COLUMN IF EXISTS " + col);
                    log.info("Dropped orphaned account_settings.{} column (not in current entity).", col);
                } catch (Exception e) {
                    // WARN so we actually notice if a drop starts failing (e.g.
                    // an unexpected FK referencing one of these columns),
                    // rather than silently carrying legacy rubbish forever.
                    log.warn("Could not drop orphaned account_settings.{}: {}", col, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not reconcile account_settings columns: {} — "
                    + "if the table doesn't exist yet this is expected on first boot.",
                    e.getMessage());
        }
    }

    /**
     * Adds {@code plus_account_active} + {@code plus_account_checked_at}. The active
     * flag is populated by the scraper on the next dorf1 hit, but we default existing
     * rows to {@code false} so the NOT NULL constraint is satisfied immediately.
     */
    private void ensurePlusAccountColumns() {
        try {
            boolean hasActive = columnExists("account", "plus_account_active");
            if (!hasActive) {
                jdbcTemplate.execute(
                        "ALTER TABLE account ADD COLUMN plus_account_active BOOLEAN NOT NULL DEFAULT FALSE");
                log.info("Added account.plus_account_active column (default FALSE)");
            }
            boolean hasChecked = columnExists("account", "plus_account_checked_at");
            if (!hasChecked) {
                jdbcTemplate.execute(
                        "ALTER TABLE account ADD COLUMN plus_account_checked_at TIMESTAMP WITHOUT TIME ZONE");
                log.info("Added account.plus_account_checked_at column");
            }
        } catch (Exception e) {
            // WARN not DEBUG — if the column doesn't land, the scraper will fail to
            // persist plus status and the queue scheduler will silently fall back to
            // "no plus" for every account. That's technically correct but confusing,
            // so surface the error loudly.
            log.warn("Could not ensure plus_account columns on account table: {} — "
                    + "if the account table doesn't exist yet this is expected on first boot.",
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
