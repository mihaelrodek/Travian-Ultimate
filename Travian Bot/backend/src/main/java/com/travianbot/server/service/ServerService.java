package com.travianbot.server.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.intel.alliance.domain.MapAlliance;
import com.travianbot.intel.alliance.persistence.MapAllianceMemberRepository;
import com.travianbot.intel.alliance.persistence.MapAllianceRepository;
import com.travianbot.intel.domain.MapVillage;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.intel.persistence.MapVillagePopulationSnapshotRepository;
import com.travianbot.intel.persistence.MapVillageRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifecycle + lookup service for {@link Server} rows.
 * <p>
 * Owns the canonical-url logic so callers never have to think about trailing
 * slashes. The {@link #seedFromAccounts()} helper is called on boot by
 * {@link ServerSeeder} and exposed as a REST endpoint so the user can
 * re-seed manually after adding accounts.
 * <p>
 * Hard deletes cascade to the {@code map_player} / {@code map_village} rows
 * scoped to the same server — those tables are server-scoped intel data, not
 * something we want orphaned in the database after the user drops the
 * corresponding {@code Server} row.
 */
@Service
@Transactional
public class ServerService {

    private static final Logger log = LoggerFactory.getLogger(ServerService.class);

    /**
     * Modern Travian Legends URL pattern — matches the {@code x{SPEED}} chunk
     * inside any {@code ts{N}.x{SPEED}.{community}.travian.com} host. The
     * speed group is captured so callers can extract the multiplier.
     */
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            "(?i)\\bx(\\d{1,2})\\b"
    );

    /**
     * Default map half-width for x10 sprint worlds — half the regular size so
     * a 1-month world is still walkable end-to-end.
     */
    private static final int X10_MAP_RADIUS = 100;

    /** Default map half-width for every other speed. */
    private static final int DEFAULT_MAP_RADIUS = 200;

    private final ServerRepository serverRepository;
    private final AccountRepository accountRepository;
    private final MapPlayerRepository mapPlayerRepository;
    private final MapVillageRepository mapVillageRepository;
    private final MapVillagePopulationSnapshotRepository mapVillagePopulationSnapshotRepository;
    private final MapAllianceRepository mapAllianceRepository;
    private final MapAllianceMemberRepository mapAllianceMemberRepository;

    public ServerService(ServerRepository serverRepository,
                         AccountRepository accountRepository,
                         MapPlayerRepository mapPlayerRepository,
                         MapVillageRepository mapVillageRepository,
                         MapVillagePopulationSnapshotRepository mapVillagePopulationSnapshotRepository,
                         MapAllianceRepository mapAllianceRepository,
                         MapAllianceMemberRepository mapAllianceMemberRepository) {
        this.serverRepository = serverRepository;
        this.accountRepository = accountRepository;
        this.mapPlayerRepository = mapPlayerRepository;
        this.mapVillageRepository = mapVillageRepository;
        this.mapVillagePopulationSnapshotRepository = mapVillagePopulationSnapshotRepository;
        this.mapAllianceRepository = mapAllianceRepository;
        this.mapAllianceMemberRepository = mapAllianceMemberRepository;
    }

    /**
     * Pull the speed multiplier out of a Travian Legends URL using the
     * canonical {@code x{SPEED}} pattern. Returns null when the URL doesn't
     * match the pattern (legacy or non-Legends hosts).
     * <p>
     * Examples:
     * <pre>
     *   ts5.x1.arabics.travian.com       → 1
     *   ts100.x10.arabics.travian.com    → 10
     *   ts20.x2.europe.travian.com       → 2
     *   tx3.czsk.travian.com             → null (TOC variant, no x-speed segment)
     * </pre>
     */
    public static Integer parseSpeedFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher m = SPEED_PATTERN.matcher(url);
        if (!m.find()) return null;
        try {
            int speed = Integer.parseInt(m.group(1));
            // Sanity bound — Travian doesn't run anything above x10 today.
            // Anything weird (x99 from a random page title) is rejected.
            if (speed <= 0 || speed > 10) return null;
            return speed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Map half-width for the given speed. Returns null when the speed is
     * null/unknown so the caller can decide what to do (we usually fall back
     * to {@link #DEFAULT_MAP_RADIUS} with a warn-log).
     */
    public static Integer defaultRadiusForSpeed(Integer speed) {
        if (speed == null) return null;
        return speed == 10 ? X10_MAP_RADIUS : DEFAULT_MAP_RADIUS;
    }

    /**
     * Fill {@code speedMultiplier} / {@code mapRadius} on the given server
     * row from its URL if they are currently null. Returns {@code true} when
     * any field changed (caller decides whether to persist).
     */
    static boolean backfillSpeedFields(Server server) {
        boolean changed = false;
        if (server.getSpeedMultiplier() == null) {
            Integer parsed = parseSpeedFromUrl(server.getUrl());
            if (parsed != null) {
                server.setSpeedMultiplier(parsed);
                changed = true;
            }
        }
        if (server.getMapRadius() == null) {
            Integer radius = defaultRadiusForSpeed(server.getSpeedMultiplier());
            if (radius != null) {
                server.setMapRadius(radius);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Resolve or create a {@link Server} for {@code url}, but if we have to
     * create it, mark it as {@code active=false}. Used by the calendar scraper
     * to pre-register the URL of an announced world so we can flip it to
     * {@code active=true} the day the world goes live and start map.sql sync
     * immediately. Existing rows are returned untouched — user-managed
     * {@code active} state wins over the calendar's defaults.
     * <p>
     * Optional {@code region} and {@code gameWorld} are stamped onto the row
     * only when it is freshly created and the values are non-blank — never
     * overwrite values the user has already set by hand.
     */
    public Server getOrCreateInactive(String url, String fallbackName, String region, String gameWorld) {
        String canonical = Server.canonicalUrl(url);
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("server url is required");
        }
        final String name = (fallbackName == null || fallbackName.isBlank())
                ? canonical
                : fallbackName;
        Optional<Server> existing = serverRepository.findByUrl(canonical);
        if (existing.isPresent()) return existing.get();
        Server fresh = new Server(canonical, name);
        fresh.setActive(false);
        if (region != null && !region.isBlank()) fresh.setRegion(region.trim());
        if (gameWorld != null && !gameWorld.isBlank()) fresh.setGameWorld(gameWorld.trim());
        backfillSpeedFields(fresh);
        Server saved = serverRepository.save(fresh);
        log.info("Pre-registered inactive server id={} url={} name='{}' speed={} (from calendar)",
                saved.getId(), saved.getUrl(), saved.getName(), saved.getSpeedMultiplier());
        return saved;
    }

    /**
     * Resolve or create a {@link Server} for {@code url}. The lookup is by
     * canonical url (trailing slash stripped) so callers can pass the raw
     * {@code Account.serverUrl} value without normalising.
     */
    public Server getOrCreate(String url, String fallbackName) {
        String canonical = Server.canonicalUrl(url);
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("server url is required");
        }
        // Pre-assign the lookup result to a local so the orElseGet lambda
        // doesn't reach back into the param vars — earlier rounds of this
        // refactor blew up on "must be final or effectively final".
        final String name = (fallbackName == null || fallbackName.isBlank())
                ? canonical
                : fallbackName;
        return serverRepository.findByUrl(canonical)
                .orElseGet(() -> {
                    Server fresh = new Server(canonical, name);
                    backfillSpeedFields(fresh);
                    return serverRepository.save(fresh);
                });
    }

    /**
     * Explicit user create. Throws if a row with this canonical url already
     * exists — the controller turns that into a 409.
     */
    public Server create(String url, String name, String region, String gameWorld) {
        String canonical = Server.canonicalUrl(url);
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (serverRepository.findByUrl(canonical).isPresent()) {
            throw new ServerAlreadyExistsException(canonical);
        }
        Server fresh = new Server(canonical, name);
        if (region != null && !region.isBlank()) fresh.setRegion(region.trim());
        if (gameWorld != null && !gameWorld.isBlank()) fresh.setGameWorld(gameWorld.trim());
        backfillSpeedFields(fresh);
        Server saved = serverRepository.save(fresh);
        log.info("Created server id={} url={} name='{}' speed={}",
                saved.getId(), saved.getUrl(), saved.getName(), saved.getSpeedMultiplier());
        return saved;
    }

    /**
     * Partial update. {@code null} fields are left alone — the caller never
     * needs to round-trip a full DTO just to flip {@code active}.
     */
    public Server update(Long id, String name, String region, String gameWorld, Boolean active) {
        Server server = requireById(id);
        if (name != null && !name.isBlank()) server.setName(name.trim());
        if (region != null) server.setRegion(region.isBlank() ? null : region.trim());
        if (gameWorld != null) server.setGameWorld(gameWorld.isBlank() ? null : gameWorld.trim());
        if (active != null) server.setActive(active);
        return serverRepository.save(server);
    }

    /**
     * Hard delete plus the intel-data cascade. We delete map_player / map_village
     * BEFORE the {@link Server} row so a partial failure leaves nothing
     * orphaned by the unique-url constraint — re-creating the same url later
     * still works cleanly.
     */
    public void delete(Long id) {
        Server server = requireById(id);
        Long serverId = server.getId();
        // Order matters: wipe alliance MEMBERS first (they FK-by-id to alliances),
        // then alliances, then villages, then players — same shape as a manual
        // tear-down so a partial failure doesn't leave dangling rows visible.
        int membersWiped = wipeMapAllianceMembers(serverId);
        int alliancesWiped = wipeMapAlliances(serverId);
        int playersWiped = wipeMapPlayers(serverId);
        int villagesWiped = wipeMapVillages(serverId);
        serverRepository.delete(server);
        log.info("Deleted server id={} url={} — wiped {} map_player, {} map_village, {} map_alliance, {} map_alliance_member rows",
                serverId, server.getUrl(), playersWiped, villagesWiped, alliancesWiped, membersWiped);
    }

    private int wipeMapPlayers(Long serverId) {
        long before = mapPlayerRepository.count();
        mapPlayerRepository.deleteAllByServerId(serverId);
        long after = mapPlayerRepository.count();
        return (int) Math.max(0, before - after);
    }

    private int wipeMapVillages(Long serverId) {
        // Wipe population-history snapshots BEFORE the parent village rows
        // so we never leave orphan rows referencing a missing village id.
        List<Long> villageIds = new ArrayList<>();
        for (MapVillage v : mapVillageRepository.findAllByServerId(serverId)) {
            villageIds.add(v.getId());
        }
        if (!villageIds.isEmpty()) {
            mapVillagePopulationSnapshotRepository.deleteAllByMapVillageIdIn(villageIds);
        }
        long before = mapVillageRepository.count();
        mapVillageRepository.deleteAllByServerId(serverId);
        long after = mapVillageRepository.count();
        return (int) Math.max(0, before - after);
    }

    /**
     * Wipe alliance roster rows belonging to this server. The member table
     * is FK-by-id on the alliance table, so we collect the alliance ids
     * first then bulk-delete by parent id — that scales linearly with the
     * number of alliances on the server rather than the number of rosters.
     */
    private int wipeMapAllianceMembers(Long serverId) {
        List<Long> allianceIds = new ArrayList<>();
        for (MapAlliance a : mapAllianceRepository.findAllByServerIdOrderByMemberCountDesc(serverId)) {
            allianceIds.add(a.getId());
        }
        if (allianceIds.isEmpty()) return 0;
        long before = mapAllianceMemberRepository.count();
        mapAllianceMemberRepository.deleteAllByMapAllianceIdIn(allianceIds);
        long after = mapAllianceMemberRepository.count();
        return (int) Math.max(0, before - after);
    }

    private int wipeMapAlliances(Long serverId) {
        long before = mapAllianceRepository.count();
        mapAllianceRepository.deleteAllByServerId(serverId);
        long after = mapAllianceRepository.count();
        return (int) Math.max(0, before - after);
    }

    @Transactional(readOnly = true)
    public List<Server> listAll() {
        return serverRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Server> listActive() {
        return serverRepository.findAllByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Server> findById(Long id) {
        return serverRepository.findById(id);
    }

    public Server requireById(Long id) {
        return serverRepository.findById(id)
                .orElseThrow(() -> new ServerNotFoundException(id));
    }

    /**
     * Walk every existing {@link Server} row whose {@code speedMultiplier} or
     * {@code mapRadius} is still null and fill them from the URL. Idempotent.
     * Returns the number of rows updated.
     * <p>
     * Called on boot by {@code ServerSeeder} so legacy rows imported before
     * those columns existed get backfilled without manual SQL.
     */
    public int backfillSpeedAndRadius() {
        List<Server> all = serverRepository.findAll();
        int updated = 0;
        for (Server s : all) {
            if (s.getSpeedMultiplier() != null && s.getMapRadius() != null) continue;
            if (backfillSpeedFields(s)) {
                serverRepository.save(s);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("ServerService.backfillSpeedAndRadius: filled {} legacy row(s)", updated);
        }
        return updated;
    }

    /**
     * Walk every {@link Account}, ensure a {@link Server} row exists matching
     * its canonical serverUrl. Used by the boot seeder so first-run installs
     * pre-populate the picker with whatever accounts already existed under
     * the old account-scoped intel module.
     *
     * @return number of new {@link Server} rows created
     */
    public int seedFromAccounts() {
        List<Account> accounts = accountRepository.findAll();
        Set<String> seen = new HashSet<>();
        int created = 0;
        for (Account account : accounts) {
            String canonical = Server.canonicalUrl(account.getServerUrl());
            if (canonical == null || canonical.isBlank()) continue;
            if (!seen.add(canonical)) continue; // already created this url in THIS pass

            if (serverRepository.findByUrl(canonical).isEmpty()) {
                String fallback = (account.getNickname() != null && !account.getNickname().isBlank())
                        ? account.getNickname()
                        : account.getUsername() + " server";
                Server fresh = new Server(canonical, fallback);
                backfillSpeedFields(fresh);
                serverRepository.save(fresh);
                created++;
            }
        }
        return created;
    }

    /**
     * How many local {@link Account}s reference the same canonical url as
     * the given server. Used by the controller to enrich the list response
     * for the picker ("Server X · 2 accounts on it").
     */
    @Transactional(readOnly = true)
    public long countAccountsOn(Server server) {
        String canonical = Server.canonicalUrl(server.getUrl());
        if (canonical == null) return 0;
        return accountRepository.findAll().stream()
                .filter(a -> canonical.equals(Server.canonicalUrl(a.getServerUrl())))
                .count();
    }
}
