package com.travianbot.village.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot startup cleanup for the (0|0) sentinel villages that an earlier
 * version of {@code ScrapingService} used to create whenever the sidebar or
 * header parser couldn't resolve real coordinates. Those rows showed up in
 * the villages UI as "Unknown (0|0)" and were never valid in-game targets —
 * they can't be navigated to via {@code ?newdid=} and no scrape for them
 * ever succeeded.
 * <p>
 * The scraper itself has been hardened to skip sidebar entries without
 * coords and to throw instead of fabricating a placeholder from the header,
 * so no new (0|0) villages should appear after this runs. This bean just
 * cleans up rows already in the DB from before the fix.
 * <p>
 * Idempotent: a clean database is a no-op. Safe to leave in place forever.
 * We key off coordinates rather than name so renames don't mask the sentinel.
 */
@Component
public class ZeroCoordinateVillageCleanup {

    private static final Logger log = LoggerFactory.getLogger(ZeroCoordinateVillageCleanup.class);

    private final JdbcTemplate jdbcTemplate;

    public ZeroCoordinateVillageCleanup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void wipeZeroCoordinateVillages() {
        try {
            // Cascade order: anything that FKs into village and isn't set up with
            // ON DELETE CASCADE needs to be cleared first. Building / resource_field /
            // building_queue_item all reference village_id — we wipe their child
            // rows for (0|0) villages before removing the villages themselves.
            Long[] villageIds = jdbcTemplate.queryForList(
                            "SELECT id FROM village WHERE coordinate_x = 0 AND coordinate_y = 0",
                            Long.class)
                    .toArray(new Long[0]);
            if (villageIds.length == 0) {
                return;
            }

            String inClause = "(" + String.join(",", java.util.Collections.nCopies(villageIds.length, "?")) + ")";
            int queueRows = jdbcTemplate.update(
                    "DELETE FROM building_queue_item WHERE village_id IN " + inClause,
                    (Object[]) villageIds);
            int buildingRows = jdbcTemplate.update(
                    "DELETE FROM building WHERE village_id IN " + inClause,
                    (Object[]) villageIds);
            int fieldRows = jdbcTemplate.update(
                    "DELETE FROM resource_field WHERE village_id IN " + inClause,
                    (Object[]) villageIds);
            int villageRows = jdbcTemplate.update(
                    "DELETE FROM village WHERE coordinate_x = 0 AND coordinate_y = 0");

            log.info("Cleaned up zero-coordinate sentinel villages: {} village(s), {} queue row(s), "
                            + "{} building(s), {} resource field(s)",
                    villageRows, queueRows, buildingRows, fieldRows);
        } catch (Exception e) {
            // WARN, not DEBUG — if this fails silently, the bogus rows live forever and
            // we'll lose a day debugging why the frontend filter isn't enough. A clear
            // log line is worth the noise on the rare first boot where the tables
            // haven't been created yet.
            log.warn("Zero-coordinate village cleanup skipped: {} — expected on first boot "
                    + "before Hibernate creates the village table.", e.getMessage());
        }
    }
}
