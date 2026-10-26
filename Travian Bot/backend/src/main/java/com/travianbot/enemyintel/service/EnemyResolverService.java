package com.travianbot.enemyintel.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.enemyintel.domain.EnemyPlayer;
import com.travianbot.enemyintel.persistence.EnemyPlayerRepository;
import com.travianbot.incoming.domain.IncomingAttack;
import com.travianbot.incoming.persistence.IncomingAttackRepository;
import com.travianbot.intel.domain.MapPlayer;
import com.travianbot.intel.persistence.MapPlayerRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.service.ServerService;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the loose-ish identity link between {@link IncomingAttack#getAttackerName()}
 * (a free-form string off the rally-point page) and a concrete
 * {@link EnemyPlayer} row. Strategy:
 *
 * <ol>
 *   <li>If an EnemyPlayer with this {@code (accountId, name)} already
 *       exists, return it.</li>
 *   <li>Otherwise look up a {@link MapPlayer} on the same server (resolved
 *       from the account's serverUrl via {@link ServerService}) whose
 *       {@code playerName} matches case-insensitively, derive
 *       {@code travianUid} from it, and create a fresh EnemyPlayer.</li>
 *   <li>If no MapPlayer matches (the daily map.sql hasn't run yet, or the
 *       attacker is a Nature/Natars NPC we skip), return empty — the caller
 *       leaves the IncomingAttack unresolved and tries again next cycle.</li>
 * </ol>
 *
 * <p><strong>Server-scoping note:</strong> {@link EnemyPlayer} stays
 * account-scoped externally (per-account "who attacked me" view) but the
 * MapPlayer lookup is server-scoped — we resolve the account's
 * {@link Server} first and look up by {@code serverId}. Multiple accounts on
 * the same server therefore share a single MapPlayer row but get their own
 * EnemyPlayer rows, which is the desired UX.
 *
 * Name uniqueness is per-server (Travian enforces it) so the (accountId,
 * name) tuple is effectively a hard key once map.sql has been synced.
 */
@Service
@Transactional
public class EnemyResolverService {

    private static final Logger log = LoggerFactory.getLogger(EnemyResolverService.class);

    private static final String EVENT_ACTION = "ENEMY_RESOLVE";

    private final EnemyPlayerRepository enemyPlayerRepository;
    private final MapPlayerRepository mapPlayerRepository;
    private final IncomingAttackRepository incomingAttackRepository;
    private final AccountRepository accountRepository;
    private final ServerService serverService;
    private final BotEventService botEventService;

    public EnemyResolverService(
            EnemyPlayerRepository enemyPlayerRepository,
            MapPlayerRepository mapPlayerRepository,
            IncomingAttackRepository incomingAttackRepository,
            AccountRepository accountRepository,
            ServerService serverService,
            BotEventService botEventService
    ) {
        this.enemyPlayerRepository = enemyPlayerRepository;
        this.mapPlayerRepository = mapPlayerRepository;
        this.incomingAttackRepository = incomingAttackRepository;
        this.accountRepository = accountRepository;
        this.serverService = serverService;
        this.botEventService = botEventService;
    }

    /**
     * Find or create an {@link EnemyPlayer} for {@code attackerName} on this
     * account. Empty result means we couldn't find a corresponding
     * {@link MapPlayer} — the caller treats that as "try again later".
     */
    public Optional<EnemyPlayer> resolveByName(Long accountId, String attackerName) {
        if (accountId == null || attackerName == null || attackerName.isBlank()) {
            return Optional.empty();
        }
        String trimmed = attackerName.trim();

        // Fast path — EnemyPlayer already exists for this name on this account.
        // We can't query EnemyPlayerRepository by name alone because the repo
        // is keyed on uid; the cheap path is to scan recent rows. In practice
        // the row count per account is small (dozens), so the linear scan is
        // fine — but if the catalogue grows we'd add a finder by name.
        Optional<EnemyPlayer> existing = enemyPlayerRepository
                .findAllByAccountIdOrderByLastScrapedAtDesc(accountId)
                .stream()
                .filter(p -> trimmed.equalsIgnoreCase(p.getPlayerName()))
                .findFirst();
        if (existing.isPresent()) return existing;

        // Resolve the account's server up-front so the MapPlayer lookup runs
        // against the correct server scope. Missing account / missing server
        // url = bail; we can't tie an attacker name to a uid without knowing
        // which Travian world to look in.
        Optional<Account> accountOpt = accountRepository.findById(accountId);
        if (accountOpt.isEmpty()) {
            log.debug("EnemyResolver: account {} not found", accountId);
            return Optional.empty();
        }
        Account account = accountOpt.get();
        String serverUrl = account.getServerUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            log.debug("EnemyResolver: account {} has no serverUrl", accountId);
            return Optional.empty();
        }
        // Pre-resolve to a final local so the lambda below stays effectively-final-safe.
        final String fallbackName = (account.getNickname() != null && !account.getNickname().isBlank())
                ? account.getNickname()
                : account.getUsername() + " server";
        Server server = serverService.getOrCreate(serverUrl, fallbackName);
        final Long serverId = server.getId();

        // Resolve through MapPlayer (server-scoped) to get the uid. We use
        // findAll(spec, page-of-1) rather than findOne() because two map.sql
        // rows with the same case-folded name on the same server would make
        // findOne() throw — extremely unlikely but recoverable.
        Specification<MapPlayer> nameMatch = (root, query, cb) -> {
            Predicate serverPred = cb.equal(root.get("serverId"), serverId);
            Predicate namePred = cb.equal(
                    cb.lower(root.get("playerName")),
                    trimmed.toLowerCase(Locale.ROOT)
            );
            return cb.and(serverPred, namePred);
        };
        List<MapPlayer> candidates = mapPlayerRepository
                .findAll(nameMatch, PageRequest.of(0, 1))
                .getContent();
        Optional<MapPlayer> hit = candidates.stream().findFirst();

        if (hit.isEmpty()) {
            log.debug("EnemyResolver: no MapPlayer for server {} (account {}) name '{}' — map.sql sync may be stale",
                    serverId, accountId, trimmed);
            return Optional.empty();
        }

        MapPlayer mp = hit.get();
        // Defensive — if a row somehow snuck in between the existence check
        // and the insert (concurrent resolve calls), fall back to the unique
        // constraint's natural deduping path.
        Optional<EnemyPlayer> raceLossWinner = enemyPlayerRepository
                .findFirstByAccountIdAndTravianUid(accountId, mp.getTravianUid());
        if (raceLossWinner.isPresent()) return raceLossWinner;

        EnemyPlayer fresh = new EnemyPlayer(accountId, mp.getTravianUid(), mp.getPlayerName());
        fresh.setTribeId(mp.getTribeId());
        EnemyPlayer saved = enemyPlayerRepository.save(fresh);
        log.info("EnemyResolver: created EnemyPlayer id={} for account {} uid={} name='{}'",
                saved.getId(), accountId, saved.getTravianUid(), saved.getPlayerName());
        return Optional.of(saved);
    }

    /**
     * Walk every {@link IncomingAttack} on this account whose
     * {@code detectedAt} is inside {@code window}, calling
     * {@link #resolveByName} for any non-null {@code attackerName}. Returns
     * the number of EnemyPlayer rows we created (existing rows count as 0).
     * <p>
     * Each attack's id is also stamped onto the resolved EnemyPlayer's
     * {@code lastInteractionAttackId} so the UI can deep-link from "enemy"
     * back to "the attack that flagged them".
     */
    public int registerFromRecentAttacks(Long accountId, Duration window) {
        if (accountId == null || window == null) return 0;
        Instant cutoff = Instant.now().minus(window);
        // Future-arrival query gives us upcoming attacks too; the resolver
        // doesn't care whether the attack has landed or not, only that we
        // recorded it. The "future" finder skips already-landed attacks which
        // is what we want for "recent" — anything older than now-window AND
        // already arrived is also stale to the resolver.
        List<IncomingAttack> attacks = incomingAttackRepository
                .findAllByAccountIdAndArrivalAtAfter(accountId, cutoff);

        int created = 0;
        for (IncomingAttack a : attacks) {
            String name = a.getAttackerName();
            if (name == null || name.isBlank()) continue;
            // Detect new-row creation by checking whether the underlying
            // MapPlayer's uid mapped to an EnemyPlayer row BEFORE we resolved.
            // Going through the count() of the whole table would over-count
            // when multiple accounts are resolving concurrently; this targeted
            // check stays correct under contention.
            boolean preExisted = enemyPlayerRepository
                    .findAllByAccountIdOrderByLastScrapedAtDesc(accountId)
                    .stream()
                    .anyMatch(p -> name.equalsIgnoreCase(p.getPlayerName()));
            Optional<EnemyPlayer> resolved = resolveByName(accountId, name);
            if (resolved.isPresent()) {
                resolved.get().setLastInteractionAttackId(a.getId());
                if (!preExisted) created++;
            }
        }

        if (created > 0) {
            botEventService.record(accountId, null, EVENT_ACTION,
                    BotEventOutcome.OK,
                    created + " new enemy player(s) registered from recent attacks");
        }
        return created;
    }
}
