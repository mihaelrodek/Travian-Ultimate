package com.travianbot.farmlist.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.botlog.domain.BotEventOutcome;
import com.travianbot.botlog.service.BotEventService;
import com.travianbot.farmlist.domain.FarmlistTarget;
import com.travianbot.farmlist.domain.RaidReport;
import com.travianbot.farmlist.persistence.FarmlistTargetRepository;
import com.travianbot.farmlist.persistence.RaidReportRepository;
import com.travianbot.farmlist.scraper.RaidReportDetailParser;
import com.travianbot.farmlist.scraper.RaidReportDetailParser.ParsedReportDetail;
import com.travianbot.farmlist.scraper.RaidReportListParser;
import com.travianbot.farmlist.scraper.RaidReportListParser.ParsedReportRowSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Walks the in-game report list, picks up every raid-shaped report we
 * haven't seen yet, parses it, persists a {@link RaidReport}, and feeds it
 * into the analyzer rollup.
 * <p>
 * Defensive by design — one bad row must not abort the whole tick. Every
 * per-row failure is logged at WARN and the loop continues.
 */
@Service
@Transactional
public class RaidReportScraperService {

    private static final Logger log = LoggerFactory.getLogger(RaidReportScraperService.class);

    private final RaidReportRepository raidReportRepository;
    private final FarmlistTargetRepository farmlistTargetRepository;
    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final BotEventService botEventService;
    private final FarmlistAnalyzerService analyzerService;

    public RaidReportScraperService(
            RaidReportRepository raidReportRepository,
            FarmlistTargetRepository farmlistTargetRepository,
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            BotEventService botEventService,
            FarmlistAnalyzerService analyzerService
    ) {
        this.raidReportRepository = raidReportRepository;
        this.farmlistTargetRepository = farmlistTargetRepository;
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.botEventService = botEventService;
        this.analyzerService = analyzerService;
    }

    /**
     * Scrape new raid reports for an account. Requires an open + logged-in
     * browser session — throws {@link IllegalStateException} if none.
     * Returns the number of new reports persisted on this run.
     */
    public int scrapeForAccount(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new IllegalStateException(
                    "Account " + accountId + " has no open browser session — log in first.");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        String listUrl = account.getServerUrl() + "/berichte.php?t=1";
        log.info("Scraping raid reports for account {}: {}", accountId, listUrl);

        String listHtml;
        try {
            listHtml = client.get(listUrl);
        } catch (RuntimeException e) {
            log.warn("Failed to load report list for account {}: {}", accountId, e.getMessage());
            botEventService.record(accountId, null, "RAID_REPORT_SCRAPE",
                    BotEventOutcome.ERROR, "Could not load report list: " + e.getMessage());
            throw e;
        }

        List<ParsedReportRowSummary> rows = RaidReportListParser.parse(listHtml);
        log.info("Parsed {} candidate report rows for account {}", rows.size(), accountId);

        int saved = 0;
        for (ParsedReportRowSummary row : rows) {
            if (row.travianReportId() == null) continue;
            try {
                Optional<RaidReport> existing = raidReportRepository
                        .findFirstByAccountIdAndTravianReportId(accountId, row.travianReportId());
                if (existing.isPresent()) continue;

                RaidReport saved1 = scrapeAndSaveSingle(account, client, row);
                if (saved1 != null) {
                    saved++;
                    analyzerService.ingest(saved1);
                }
            } catch (RuntimeException e) {
                // One bad row must not abort the loop. Log warn and keep going.
                log.warn("Failed to scrape report {} for account {}: {}",
                        row.travianReportId(), accountId, e.getMessage());
            }
        }

        BotEventOutcome outcome = (saved >= 0) ? BotEventOutcome.OK : BotEventOutcome.WARN;
        botEventService.record(accountId, null, "RAID_REPORT_SCRAPE", outcome,
                "Scraped " + saved + " new report" + (saved == 1 ? "" : "s"));
        return saved;
    }

    /**
     * Hits the detail page for a single report, parses it, attributes it to a
     * farmlist target by coords, and persists. Returns the saved entity or
     * {@code null} if the detail page can't be parsed at all.
     */
    private RaidReport scrapeAndSaveSingle(Account account,
                                           BrowserTravianClient client,
                                           ParsedReportRowSummary row) {
        String detailUrl = account.getServerUrl() + "/berichte.php?id=" + row.travianReportId();
        String html;
        try {
            html = client.get(detailUrl);
        } catch (RuntimeException e) {
            log.warn("Could not fetch detail for report {}: {}", row.travianReportId(), e.getMessage());
            return null;
        }
        ParsedReportDetail detail = RaidReportDetailParser.parse(html);

        // Prefer the detail page's coords (more reliable) but fall back to the
        // list-row coords for skins where the detail headline doesn't carry them.
        Integer x = (detail.targetX() != null) ? detail.targetX() : row.targetX();
        Integer y = (detail.targetY() != null) ? detail.targetY() : row.targetY();
        if (x == null || y == null) {
            log.debug("Skipping report {} — no usable target coords", row.travianReportId());
            return null;
        }

        // Attribute to a target (and therefore a farmlist) by coords, scoped
        // to the account. When the same coord is in multiple lists we take
        // the first match — see FarmlistTargetRepository javadoc.
        Optional<FarmlistTarget> match = farmlistTargetRepository
                .findFirstByFarmlistAccountIdAndCoordinateXAndCoordinateY(
                        account.getId(), x, y);

        if (match.isEmpty()) {
            // No attribution — skip persistence. We never raid coords that
            // aren't in a farmlist via the scheduler, so this is almost
            // always a one-off manual raid the user fired outside the bot.
            // Saving it would either require a sentinel farmlistId (kludge)
            // or letting it be null (column is NOT NULL on this entity).
            // We prefer to drop than to introduce a sentinel.
            log.debug("Report {} at ({}|{}) doesn't match any farmlist target — dropping",
                    row.travianReportId(), x, y);
            return null;
        }
        Long farmlistId = match.get().getFarmlist().getId();
        Long farmlistTargetId = match.get().getId();

        Instant returnedAt = row.raidReturnedAt() != null ? row.raidReturnedAt() : Instant.now();
        RaidReport report = new RaidReport(account.getId(), farmlistId,
                row.travianReportId(), x, y, returnedAt);
        report.setFarmlistTargetId(farmlistTargetId);
        report.setTargetVillageName(
                (detail.targetVillageName() != null) ? detail.targetVillageName() : row.targetVillageName());
        report.setLoot(detail.woodLooted(), detail.clayLooted(), detail.ironLooted(), detail.cropLooted());
        report.setCarryCapacityTotal(detail.carryCapacityTotal());
        report.setWasFullHaul(detail.wasFullHaul());
        report.setOurTroopsLost(detail.ourTroopsLost());
        report.setEnemyTroopsLost(detail.enemyTroopsLost());
        report.setOutcome(detail.outcome());

        return raidReportRepository.save(report);
    }

    /** Read-only proxy — last raid reports attributed to one target, latest first. */
    @Transactional(readOnly = true)
    public List<RaidReport> listFor(Long accountId, Long farmlistTargetId) {
        // accountId is part of the API path for symmetry with the rest of the
        // controller — the actual filter is by farmlistTargetId because that's
        // what's indexed.
        return raidReportRepository.findAllByFarmlistTargetIdOrderByRaidReturnedAtDesc(farmlistTargetId);
    }

    /** Read-only proxy — recent reports across the account. */
    @Transactional(readOnly = true)
    public List<RaidReport> listRecent(Long accountId, Duration window) {
        Duration safe = (window != null && !window.isNegative() && !window.isZero())
                ? window : Duration.ofHours(1);
        Instant cutoff = Instant.now().minus(safe);
        return raidReportRepository.findAllByAccountIdAndRaidReturnedAtAfter(accountId, cutoff);
    }
}
