package com.travianbot.enemyintel.api;

import com.travianbot.enemyintel.api.dto.EnemyPlayerDetailResponse;
import com.travianbot.enemyintel.api.dto.EnemyPlayerResponse;
import com.travianbot.enemyintel.api.dto.EnemyProfileSnapshotResponse;
import com.travianbot.enemyintel.domain.EnemyPlayer;
import com.travianbot.enemyintel.domain.EnemyProfileSnapshot;
import com.travianbot.enemyintel.persistence.EnemyPlayerRepository;
import com.travianbot.enemyintel.persistence.EnemyProfileSnapshotRepository;
import com.travianbot.enemyintel.service.EnemyProfileScraperService;
import com.travianbot.enemyintel.service.EnemyResolverService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP surface for the enemy-intel module. Every route is per-account
 * — the {@code accountId} path variable scopes all reads and writes.
 *
 * <ul>
 *   <li>{@code GET    /api/accounts/{accountId}/enemyintel/enemies} —
 *       list every tracked enemy (latest summary).</li>
 *   <li>{@code GET    /api/accounts/{accountId}/enemyintel/enemies/{id}} —
 *       detail + last 20 snapshots.</li>
 *   <li>{@code POST   /api/accounts/{accountId}/enemyintel/enemies/{id}/scrape} —
 *       force-scrape one enemy now.</li>
 *   <li>{@code POST   /api/accounts/{accountId}/enemyintel/register-from-recent-attacks} —
 *       manual resolver run. Returns {@code {created: N}}.</li>
 *   <li>{@code POST   /api/accounts/{accountId}/enemyintel/refresh-stale} —
 *       manual scraper run. Returns {@code {scraped: N}}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/enemyintel")
public class EnemyIntelController {

    private final EnemyPlayerRepository enemyPlayerRepository;
    private final EnemyProfileSnapshotRepository snapshotRepository;
    private final EnemyResolverService resolverService;
    private final EnemyProfileScraperService scraperService;

    public EnemyIntelController(
            EnemyPlayerRepository enemyPlayerRepository,
            EnemyProfileSnapshotRepository snapshotRepository,
            EnemyResolverService resolverService,
            EnemyProfileScraperService scraperService
    ) {
        this.enemyPlayerRepository = enemyPlayerRepository;
        this.snapshotRepository = snapshotRepository;
        this.resolverService = resolverService;
        this.scraperService = scraperService;
    }

    @GetMapping("/enemies")
    public List<EnemyPlayerResponse> list(@PathVariable Long accountId) {
        return enemyPlayerRepository.findAllByAccountIdOrderByLastScrapedAtDesc(accountId).stream()
                .map(EnemyPlayerResponse::from)
                .toList();
    }

    @GetMapping("/enemies/{enemyPlayerId}")
    public ResponseEntity<EnemyPlayerDetailResponse> detail(
            @PathVariable Long accountId,
            @PathVariable Long enemyPlayerId
    ) {
        Optional<EnemyPlayer> player = enemyPlayerRepository.findById(enemyPlayerId);
        if (player.isEmpty() || !accountId.equals(player.get().getAccountId())) {
            // Mismatched account id behaves as 404 from the client's POV —
            // we never want to leak that "this id belongs to a different
            // account" via a 403 vs 404 distinction.
            return ResponseEntity.notFound().build();
        }
        List<EnemyProfileSnapshot> snaps = snapshotRepository
                .findTop20ByEnemyPlayerIdOrderByCapturedAtDesc(enemyPlayerId);
        return ResponseEntity.ok(EnemyPlayerDetailResponse.from(player.get(), snaps));
    }

    @PostMapping("/enemies/{enemyPlayerId}/scrape")
    public ResponseEntity<EnemyProfileSnapshotResponse> scrapeOne(
            @PathVariable Long accountId,
            @PathVariable Long enemyPlayerId
    ) {
        Optional<EnemyPlayer> player = enemyPlayerRepository.findById(enemyPlayerId);
        if (player.isEmpty() || !accountId.equals(player.get().getAccountId())) {
            return ResponseEntity.notFound().build();
        }
        Optional<EnemyProfileSnapshot> snap = scraperService.scrape(enemyPlayerId);
        // 200 with body when we got something, 200 with no-content body when
        // the scrape returned empty — clients can branch on the boolean
        // without parsing the snapshot. We deliberately don't 204 because
        // the UI wants to flash the "no fields parsed" message back.
        return snap.map(s -> ResponseEntity.ok(EnemyProfileSnapshotResponse.from(s)))
                .orElseGet(() -> ResponseEntity.ok().build());
    }

    @PostMapping("/register-from-recent-attacks")
    public Map<String, Integer> registerFromRecentAttacks(
            @PathVariable Long accountId,
            @RequestParam(name = "windowHours", required = false, defaultValue = "48") int windowHours
    ) {
        int safe = Math.max(1, Math.min(windowHours, 24 * 30));
        int created = resolverService.registerFromRecentAttacks(accountId, Duration.ofHours(safe));
        return Map.of("created", created);
    }

    @PostMapping("/refresh-stale")
    public Map<String, Integer> refreshStale(
            @PathVariable Long accountId,
            @RequestParam(name = "stalenessHours", required = false, defaultValue = "6") int stalenessHours,
            @RequestParam(name = "max", required = false, defaultValue = "10") int max
    ) {
        int safeStaleness = Math.max(0, Math.min(stalenessHours, 24 * 30));
        int safeMax = Math.max(1, Math.min(max, 100));
        int scraped = scraperService.refreshStaleForAccount(
                accountId, Duration.ofHours(safeStaleness), safeMax);
        return Map.of("scraped", scraped);
    }
}
