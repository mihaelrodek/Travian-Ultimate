package com.travianbot.statistics.service;

import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.statistics.api.dto.HistoryPointDto;
import com.travianbot.statistics.api.dto.HistoryResponse;
import com.travianbot.statistics.api.dto.Top10Response;
import com.travianbot.statistics.api.dto.Top10RowDto;
import com.travianbot.statistics.domain.StatCategory;
import com.travianbot.statistics.domain.StatSnapshot;
import com.travianbot.statistics.domain.Top10Entry;
import com.travianbot.statistics.persistence.StatSnapshotRepository;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side service for the statistics feature. Loads the latest two
 * snapshots for an account+category and computes per-row deltas against the
 * previous one (matched by player name, not by rank — ranks shuffle).
 * <p>
 * The write path (capturing a new snapshot) lives in
 * {@code StatisticsScrapingService}; this service doesn't know how snapshots
 * got there, which makes it trivial to unit-test with fixture data.
 */
@Service
@Transactional(readOnly = true)
public class StatisticsService {

    private final StatSnapshotRepository repository;

    public StatisticsService(StatSnapshotRepository repository) {
        this.repository = repository;
    }

    public Top10Response getTop10(Long accountId, StatCategory category) {
        List<StatSnapshot> recent = repository
                .findAllByAccountIdAndCategoryOrderByCapturedAtDesc(accountId, category, Limit.of(2));
        if (recent.isEmpty()) return Top10Response.empty(accountId, category);

        StatSnapshot latest = recent.get(0);
        StatSnapshot previous = recent.size() >= 2 ? recent.get(1) : null;

        Map<String, Top10Entry> prevByPlayer = new HashMap<>();
        if (previous != null) {
            for (Top10Entry e : previous.getEntries()) {
                if (e.getPlayerName() != null) prevByPlayer.put(e.getPlayerName(), e);
            }
        }

        List<Top10RowDto> rows = new ArrayList<>(latest.getEntries().size());
        for (Top10Entry e : latest.getEntries()) {
            Top10Entry prev = prevByPlayer.get(e.getPlayerName());
            rows.add(new Top10RowDto(
                    e.getRank(),
                    e.getPlayerName(),
                    e.getScore(),
                    deltaLong(e.getScore(), prev == null ? null : prev.getScore()),
                    // Rank delta is inverted: going from rank 5 → rank 2 is +3 (positive = improvement).
                    prev == null ? null : (prev.getRank() - e.getRank())
            ));
        }

        return new Top10Response(
                accountId,
                category,
                latest.getServerUrl(),
                latest.getCapturedAt(),
                previous == null ? null : previous.getCapturedAt(),
                rows
        );
    }

    /**
     * History aggregated to a single "per-snapshot" point. If a {@code playerName}
     * is passed we emit that player's row from each snapshot (or skip the
     * snapshot if they weren't in its top 10). Without a name we sum scores
     * across the whole captured top-10 — gives a server-wide trend for the
     * category that's robust to individual players falling in or out of the
     * window.
     */
    public HistoryResponse getHistory(Long accountId, StatCategory category, int hours, String playerName) {
        Instant since = Instant.now().minus(Duration.ofHours(Math.max(1, hours)));
        List<StatSnapshot> snapshots = repository
                .findAllByAccountIdAndCategoryAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(
                        accountId, category, since);

        List<HistoryPointDto> points = new ArrayList<>(snapshots.size());
        for (StatSnapshot snap : snapshots) {
            HistoryPointDto p = collapse(snap, playerName);
            if (p != null) points.add(p);
        }
        return new HistoryResponse(accountId, category, hours, playerName, points);
    }

    /**
     * Collapse one snapshot's entries to a single history point. Player-scoped:
     * return that player's row (null if absent). Aggregate: sum scores across
     * the top-10 and report the best (lowest) rank seen.
     */
    private HistoryPointDto collapse(StatSnapshot snap, String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            for (Top10Entry e : snap.getEntries()) {
                if (playerName.equals(e.getPlayerName())) {
                    return new HistoryPointDto(snap.getCapturedAt(), e.getRank(), e.getScore());
                }
            }
            return null;
        }
        long totalScore = 0;
        int topRank = Integer.MAX_VALUE;
        boolean any = false;
        boolean anyScore = false;
        for (Top10Entry e : snap.getEntries()) {
            any = true;
            if (e.getScore() != null) {
                totalScore += e.getScore();
                anyScore = true;
            }
            topRank = Math.min(topRank, e.getRank());
        }
        if (!any) return null;
        return new HistoryPointDto(
                snap.getCapturedAt(),
                topRank == Integer.MAX_VALUE ? null : topRank,
                anyScore ? totalScore : null
        );
    }

    private static Long deltaLong(Long now, Long prev) {
        if (now == null || prev == null) return null;
        return now - prev;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }
}
