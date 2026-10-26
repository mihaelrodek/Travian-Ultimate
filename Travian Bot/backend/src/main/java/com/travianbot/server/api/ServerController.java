package com.travianbot.server.api;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.server.api.dto.CreateServerRequest;
import com.travianbot.server.api.dto.PivotVillageDto;
import com.travianbot.server.api.dto.ServerResponse;
import com.travianbot.server.api.dto.UpdateServerRequest;
import com.travianbot.server.domain.Server;
import com.travianbot.server.service.ServerService;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the {@link Server} module. Sits beside
 * {@code /api/accounts/...} as a sibling top-level resource — servers are
 * shared across accounts, not nested under one.
 * <p>
 * The {@code /api/servers/{id}/villages} endpoint is the cross-cutting
 * "which pivot can I pick" lookup the frontend's inactive-search page needs;
 * it spans accounts + villages so it lives here rather than inside the intel
 * module.
 */
@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final AccountRepository accountRepository;
    private final VillageRepository villageRepository;

    public ServerController(ServerService serverService,
                            AccountRepository accountRepository,
                            VillageRepository villageRepository) {
        this.serverService = serverService;
        this.accountRepository = accountRepository;
        this.villageRepository = villageRepository;
    }

    @GetMapping
    public List<ServerResponse> list() {
        List<Server> all = serverService.listAll();
        return all.stream()
                .map(s -> ServerResponse.from(s, serverService.countAccountsOn(s)))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServerResponse> get(@PathVariable Long id) {
        return serverService.findById(id)
                .map(s -> ResponseEntity.ok(ServerResponse.from(s, serverService.countAccountsOn(s))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServerResponse create(@RequestBody CreateServerRequest req) {
        if (req == null || req.url() == null || req.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        Server created = serverService.create(req.url(), req.name(), req.region(), req.gameWorld());
        return ServerResponse.from(created, serverService.countAccountsOn(created));
    }

    @PutMapping("/{id}")
    public ServerResponse update(@PathVariable Long id, @RequestBody UpdateServerRequest req) {
        UpdateServerRequest body = req == null
                ? new UpdateServerRequest(null, null, null, null)
                : req;
        Server updated = serverService.update(id, body.name(), body.region(), body.gameWorld(), body.active());
        return ServerResponse.from(updated, serverService.countAccountsOn(updated));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        serverService.delete(id);
    }

    /**
     * Manual reseed trigger. Idempotent — running twice in a row creates 0
     * the second time. Useful when the user just added an account on a
     * server the bot didn't previously know about.
     */
    @PostMapping("/seed-from-accounts")
    public SeedResponse seedFromAccounts() {
        int created = serverService.seedFromAccounts();
        return new SeedResponse(created);
    }

    /**
     * Every village the user owns on this server — read across all of their
     * accounts whose canonical url matches. Backs the pivot-village picker on
     * the intel page.
     * <p>
     * {@code @Transactional} so the lazy {@code Village.account} navigation
     * inside the stream doesn't blow up on the closed-session check.
     */
    @GetMapping("/{id}/villages")
    @Transactional(readOnly = true)
    public List<PivotVillageDto> listPivotVillages(@PathVariable Long id) {
        Server server = serverService.requireById(id);
        String canonical = server.getUrl();

        Map<Long, Account> accountById = new HashMap<>();
        List<Long> matchingAccountIds = new ArrayList<>();
        for (Account a : accountRepository.findAll()) {
            if (canonical.equals(Server.canonicalUrl(a.getServerUrl()))) {
                accountById.put(a.getId(), a);
                matchingAccountIds.add(a.getId());
            }
        }
        if (matchingAccountIds.isEmpty()) return List.of();

        List<PivotVillageDto> out = new ArrayList<>();
        for (Long aid : matchingAccountIds) {
            List<Village> villages = villageRepository
                    .findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(aid);
            Account owner = accountById.get(aid);
            String accountName = (owner != null && owner.getNickname() != null && !owner.getNickname().isBlank())
                    ? owner.getNickname()
                    : (owner == null ? "?" : owner.getUsername());
            for (Village v : villages) {
                out.add(new PivotVillageDto(
                        v.getId(),
                        aid,
                        accountName,
                        v.getName(),
                        v.getCoordinateX(),
                        v.getCoordinateY()
                ));
            }
        }
        return out;
    }

    /** Tiny envelope for the seed response so the wire stays a JSON object. */
    public record SeedResponse(int created) {}
}
