package com.travianbot.statistics.scraper;

import com.travianbot.account.domain.Account;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.bot.client.browser.BrowserSessionManager;
import com.travianbot.bot.client.browser.BrowserTravianClient;
import com.travianbot.bot.scraper.NotLoggedInException;
import com.travianbot.statistics.domain.StatCategory;
import com.travianbot.statistics.domain.StatSnapshot;
import com.travianbot.statistics.domain.Top10Entry;
import com.travianbot.statistics.persistence.StatSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the Travian top-10 leaderboard page for an account and persists
 * one {@link StatSnapshot} per {@link StatCategory} table found on the page
 * (PvP, PvE, Defenders, Raiders).
 * <p>
 * Only one URL — {@code /statistics/player/top10} — is ever fetched. The four
 * category tables all live on that single page, so one HTTP round-trip plus
 * one parse yields a full "everything captured at this instant" set of
 * snapshots. Writing them in one transaction means every snapshot for a
 * given {@code capturedAt} is either fully present or fully absent, which
 * keeps the delta logic in the read service well-defined.
 */
@Service
@Transactional
public class StatisticsScrapingService {

    private static final Logger log = LoggerFactory.getLogger(StatisticsScrapingService.class);
    private static final String TOP10_PATH = "/statistics/player/top10";

    /**
     * Selector used to decide the top10 page is "ready". T4.6 Legends hydrates
     * the ranking tables via XHR after DOMContentLoaded, so reading content
     * too early returns a skeleton with an empty tbody.
     */
    private static final String TOP10_READY_SELECTOR = "table tbody tr td";
    private static final int TOP10_WAIT_MS = 8_000;

    private final BrowserSessionManager sessionManager;
    private final AccountRepository accountRepository;
    private final StatSnapshotRepository snapshotRepository;

    public StatisticsScrapingService(
            BrowserSessionManager sessionManager,
            AccountRepository accountRepository,
            StatSnapshotRepository snapshotRepository
    ) {
        this.sessionManager = sessionManager;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Scrape and persist. Returns a map of the just-persisted snapshots keyed
     * by category — every category whose table was present on the page gets
     * an entry. Throws {@link NotLoggedInException} when the browser session
     * is absent/logged out, and {@link IllegalStateException} when the page
     * yielded zero parseable rows across all four tables (HTML is dumped
     * under {@code build/debug/} for inspection).
     */
    public Map<StatCategory, StatSnapshot> capture(Long accountId) {
        BrowserTravianClient client = sessionManager.existing(accountId);
        if (client == null || !client.session().isLoggedIn()) {
            throw new NotLoggedInException(accountId);
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        String url = account.getServerUrl() + TOP10_PATH;
        log.info("Capturing top10 for account {}: {}", accountId, url);

        // Wait-for-selector variant: table bodies are XHR-hydrated and a
        // plain get() returns a skeleton the parser can't read.
        String html = client.getWaitingFor(url, TOP10_READY_SELECTOR, TOP10_WAIT_MS);
        Map<StatCategory, List<ParsedTop10Row>> parsed = Top10PageParser.parseAll(html);

        if (parsed.isEmpty()) {
            Path dumpPath = dumpDebugHtml(accountId, html);
            throw new IllegalStateException(
                    "Top10 page returned no parseable rows for account " + accountId
                            + " (URL: " + url + ", HTML dumped to: " + dumpPath + ")"
            );
        }

        // One capturedAt across all four snapshots so the UI can show a
        // single "last captured" timestamp for the page even when each tab
        // loads independently.
        Instant capturedAt = Instant.now();
        Map<StatCategory, StatSnapshot> saved = new EnumMap<>(StatCategory.class);
        for (Map.Entry<StatCategory, List<ParsedTop10Row>> entry : parsed.entrySet()) {
            StatSnapshot snap = new StatSnapshot(
                    account, account.getServerUrl(), capturedAt, entry.getKey());
            for (ParsedTop10Row row : entry.getValue()) {
                snap.addEntry(new Top10Entry(snap, row.rank(), row.playerName(), row.score()));
            }
            saved.put(entry.getKey(), snapshotRepository.save(snap));
        }
        log.info("Captured {} categor{} for account {}: {}",
                saved.size(), saved.size() == 1 ? "y" : "ies", accountId, saved.keySet());
        return saved;
    }

    /**
     * Dumps the unparseable HTML to {@code <repo>/build/debug/} so we can
     * inspect what the live server returned. Best-effort — failure to write
     * the dump doesn't mask the parse failure.
     */
    private Path dumpDebugHtml(Long accountId, String html) {
        try {
            Path dir = Paths.get("build", "debug");
            Files.createDirectories(dir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(Instant.now());
            Path file = dir.resolve("top10-account-" + accountId + "-" + ts + ".html");
            Files.writeString(file, html == null ? "" : html);
            return file.toAbsolutePath();
        } catch (IOException e) {
            log.warn("Failed to dump debug HTML: {}", e.getMessage());
            return Paths.get("(dump failed)");
        }
    }
}
