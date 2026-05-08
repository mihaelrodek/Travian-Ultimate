package com.travianbot.bot.scraper;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.persistence.FarmlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Scrapes the rally-point "Farm lists" tab for an account and upserts the
 * results into the local {@code farmlist} / {@code farmlist_target} tables.
 * <p>
 * Scraper policy: we never overwrite user-edited policy fields (type, interval,
 * groupTag) when reconciling. The scraper only owns list identity (name,
 * travianListId) and the target rows (the user's PVE/PVP/interval choices are
 * supposed to survive a rescan).
 * <p>
 * Reconciliation priority:
 * <ol>
 *   <li>(accountId, travianListId) — once we know the Travian-side id, always use it.</li>
 *   <li>(accountId, name) — fallback on first scrape before we've stored the id.</li>
 *   <li>Create new.</li>
 * </ol>
 */
@Service
@Transactional
public class FarmlistScrapingService {

    private static final Logger log = LoggerFactory.getLogger(FarmlistScrapingService.class);

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final FarmlistRepository farmlistRepository;

    public FarmlistScrapingService(
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            FarmlistRepository farmlistRepository
    ) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.farmlistRepository = farmlistRepository;
    }

    public int scrapeFarmlists(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        String url = account.getServerUrl() + "/build.php?gid=16&tt=99";
        log.info("Scraping farmlists for account {}: {}", accountId, url);
        String html = client.get(url);

        List<ParsedFarmlist> parsed = FarmlistPageParser.parse(html);
        log.info("Parsed {} farmlists for account {}", parsed.size(), accountId);

        for (ParsedFarmlist pfl : parsed) {
            upsertFarmlist(account, pfl);
        }
        return parsed.size();
    }

    private void upsertFarmlist(Account account, ParsedFarmlist pfl) {
        Farmlist fl = null;
        if (pfl.travianListId() != null) {
            fl = farmlistRepository
                    .findFirstByAccountIdAndTravianListId(account.getId(), pfl.travianListId())
                    .orElse(null);
        }
        if (fl == null) {
            fl = farmlistRepository
                    .findFirstByAccountIdAndName(account.getId(), pfl.name())
                    .orElse(null);
        }
        if (fl == null) {
            fl = new Farmlist(account, pfl.name());
            log.info("Discovered new farmlist for account {}: {} (travianId={})",
                    account.getId(), pfl.name(), pfl.travianListId());
        } else if (!Objects.equals(fl.getName(), pfl.name())) {
            // Keep the list name in sync — the user probably renamed it in-game.
            fl.update(pfl.name(), fl.getDescription(), fl.isEnabled());
        }

        if (pfl.travianListId() != null
                && !Objects.equals(fl.getTravianListId(), pfl.travianListId())) {
            fl.setTravianListId(pfl.travianListId());
        }

        replaceTargets(fl, pfl.targets());
        farmlistRepository.save(fl);
    }

    private void replaceTargets(Farmlist fl, List<ParsedFarmlistTarget> parsed) {
        // Reconcile targets by travianTargetId when available, else by coords.
        Map<Long, FarmlistTarget> byTravianId = new HashMap<>();
        Map<String, FarmlistTarget> byCoords = new HashMap<>();
        for (FarmlistTarget t : fl.getTargets()) {
            if (t.getTravianTargetId() != null) byTravianId.put(t.getTravianTargetId(), t);
            byCoords.put(coordKey(t.getCoordinateX(), t.getCoordinateY()), t);
        }

        List<FarmlistTarget> next = new ArrayList<>();
        for (ParsedFarmlistTarget p : parsed) {
            FarmlistTarget existing = (p.travianTargetId() != null)
                    ? byTravianId.remove(p.travianTargetId())
                    : null;
            if (existing == null) {
                existing = byCoords.remove(coordKey(p.coordinateX(), p.coordinateY()));
            }
            if (existing == null) {
                existing = new FarmlistTarget(fl, p.name(), p.coordinateX(), p.coordinateY(), p.troopsJson());
                existing.update(p.name(), p.coordinateX(), p.coordinateY(), p.troopsJson(), p.enabled());
            } else {
                existing.update(p.name(), p.coordinateX(), p.coordinateY(), p.troopsJson(), p.enabled());
            }
            if (p.travianTargetId() != null) existing.setTravianTargetId(p.travianTargetId());
            next.add(existing);
        }

        // Anything still in byCoords is no longer on the server — drop it.
        List<FarmlistTarget> existing = fl.getTargets();
        existing.clear();
        existing.addAll(next);
    }

    private static String coordKey(int x, int y) {
        return x + "|" + y;
    }
}
