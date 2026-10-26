package com.travianbot.server.service;

import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Boot-time data migrator + seeder for the server-scoping refactor.
 * <p>
 * Two responsibilities:
 * <ol>
 *   <li><strong>Seed {@link com.travianbot.server.domain.Server} rows from
 *       existing accounts</strong> — for every {@code Account}, ensure a
 *       canonical-url Server row exists. Idempotent.</li>
 *   <li><strong>Wipe orphaned intel data</strong> — the {@code intel} module
 *       was refactored from {@code account_id} to {@code server_id} columns.
 *       With {@code ddl-auto: update}, Hibernate adds {@code server_id} as a
 *       nullable column but leaves the old {@code account_id} column
 *       behind. Any pre-existing {@code map_player}/{@code map_village} row
 *       therefore has a {@code NULL server_id} and is unusable. We detect
 *       that and wipe both tables so the next sync repopulates cleanly.</li>
 * </ol>
 *
 * <p>TODO Phase 0.3 — replace the boot-time wipe with a proper Flyway
 * migration once the V1 schema baseline lands.
 */
@Component
public class ServerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServerSeeder.class);

    private final ServerService serverService;
    private final MapPlayerRepository mapPlayerRepository;
    private final MapVillageRepository mapVillageRepository;
    private final JdbcTemplate jdbcTemplate;

    public ServerSeeder(ServerService serverService,
                        MapPlayerRepository mapPlayerRepository,
                        MapVillageRepository mapVillageRepository,
                        JdbcTemplate jdbcTemplate) {
        this.serverService = serverService;
        this.mapPlayerRepository = mapPlayerRepository;
        this.mapVillageRepository = mapVillageRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        wipeOrphanIntelData();
        dropLegacyAccountIdColumns();
        int created = serverService.seedFromAccounts();
        if (created > 0) {
            log.info("ServerSeeder: created {} server row(s) from existing accounts", created);
        } else {
            log.debug("ServerSeeder: no new server rows to seed");
        }
        // Backfill speedMultiplier/mapRadius on rows that pre-existed the
        // columns. Safe to run on every boot — only touches NULLs.
        int backfilled = serverService.backfillSpeedAndRadius();
        if (backfilled > 0) {
            log.info("ServerSeeder: backfilled speedMultiplier/mapRadius on {} legacy server row(s)", backfilled);
        }
    }

    /**
     * Drop the orphaned {@code account_id} column from {@code map_player} and
     * {@code map_village} if it still exists. Hibernate's {@code ddl-auto:
     * update} adds new columns but never removes legacy ones, so the old
     * NOT-NULL constraint stays and blocks every new insert until we kill it
     * here.
     * <p>
     * Uses {@code DROP COLUMN IF EXISTS} so the migration is idempotent and
     * harmless on a brand-new database where the column was never created.
     * <p>
     * TODO Phase 0.3 — fold into the Flyway baseline once it lands.
     */
    private void dropLegacyAccountIdColumns() {
        for (String table : new String[]{"map_player", "map_village"}) {
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE " + table + " DROP COLUMN IF EXISTS account_id");
                log.debug("ServerSeeder: ensured {}.account_id is gone", table);
            } catch (RuntimeException e) {
                // Table may not exist yet (truly fresh DB) — that's fine.
                log.debug("ServerSeeder: drop {}.account_id skipped — {}", table, e.getMessage());
            }
        }
    }

    /**
     * Wipe map_player / map_village if Hibernate added {@code server_id} as a
     * fresh column on top of an existing table (i.e. rows exist but their
     * server_id is null). Defensive — catches everything so a missing table
     * (first-run, fresh DB) is a no-op.
     * <p>
     * TODO Phase 0.3 — drop the legacy {@code account_id} column properly via
     * Flyway once the baseline migration is in.
     */
    private void wipeOrphanIntelData() {
        try {
            Integer nullPlayers = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM map_player WHERE server_id IS NULL", Integer.class);
            Integer nullVillages = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM map_village WHERE server_id IS NULL", Integer.class);
            int orphanPlayers = nullPlayers == null ? 0 : nullPlayers;
            int orphanVillages = nullVillages == null ? 0 : nullVillages;
            if (orphanPlayers == 0 && orphanVillages == 0) {
                return;
            }
            log.warn("ServerSeeder: detected {} map_player and {} map_village rows with NULL server_id "
                            + "(legacy account-scoped data). Wiping both tables — the intel feature was "
                            + "refactored to be server-scoped and a fresh sync will repopulate them.",
                    orphanPlayers, orphanVillages);
            mapVillageRepository.deleteAllInBatch();
            mapPlayerRepository.deleteAllInBatch();
        } catch (RuntimeException e) {
            // Table likely doesn't exist yet (first boot on a brand-new DB).
            // Hibernate's ddl-auto creates it just after this runner finishes
            // on truly empty databases, but the order isn't guaranteed across
            // versions — swallow and continue.
            log.debug("ServerSeeder: orphan-intel check skipped — {}", e.getMessage());
        }
    }
}
