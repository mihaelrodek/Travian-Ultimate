package com.travianbot.aianalysis.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.aianalysis.domain.OasisScan;
import com.travianbot.aianalysis.domain.OasisTile;
import com.travianbot.aianalysis.domain.OasisType;
import com.travianbot.aianalysis.persistence.OasisScanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Backend for the AI Analysis screen — kicks off an oasis scan around a
 * center coordinate and persists the results so the dashboard has stable
 * data to read from.
 *
 * <h3>Map fetch is a TODO</h3>
 * The actual "open Travian's map and parse the tiles" is not yet wired up.
 * The browser client doesn't have a "fetch tiles around X,Y" method; the
 * Travian map endpoint varies by server generation (some expose a JSON
 * {@code /api/v1/map/position} POST, older skins use {@code /map.php?d=...}).
 * <p>
 * For now {@link #runScan} generates a deterministic mock tile spread so the
 * dashboard has something to render. The seed is the (centerX, centerY,
 * radius) tuple, so a re-scan of the same coordinate produces the same
 * tiles — this is good enough to test the UI and lets the user see the
 * shape before we wire the real scrape. The user has explicitly offered to
 * teach the bot the right map endpoint; once they do, the body of
 * {@link #runScan} swaps the mock loop for a real scrape and the rest of
 * the pipeline (tally + persist) stays the same.
 */
@Service
@Transactional
public class OasisAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OasisAnalysisService.class);

    private final OasisScanRepository scanRepository;
    private final AccountRepository accountRepository;

    public OasisAnalysisService(OasisScanRepository scanRepository,
                                AccountRepository accountRepository) {
        this.scanRepository = scanRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public Optional<OasisScan> latestForAccount(Long accountId) {
        return scanRepository.findFirstByAccountIdOrderByStartedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public List<OasisScan> historyForAccount(Long accountId) {
        return scanRepository.findAllByAccountIdOrderByStartedAtDesc(accountId);
    }

    /**
     * Run a new scan for the account around the given center coordinate.
     * <p>
     * Currently uses a deterministic mock tile generator (see class JavaDoc).
     * The persistence shape — one OasisScan parent + OasisTile children, plus
     * cached per-type counts on the parent — is the same shape the real
     * scraper will produce, so the dashboard contract stays stable when the
     * scrape lands.
     */
    public OasisScan runScan(Long accountId, int centerX, int centerY, int radius) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        OasisScan scan = new OasisScan(account, centerX, centerY, radius);
        scan = scanRepository.save(scan); // get an id before we add tiles

        int wood = 0, clay = 0, iron = 0, crop = 0, mixed = 0;
        // Seeded random so re-scanning the same (x, y, radius) reproduces the
        // same tile set. Stable across reloads → easier to demo the UI without
        // each refresh shuffling the dashboard numbers.
        Random rng = new Random(((long) centerX * 73856093L)
                ^ ((long) centerY * 19349663L) ^ ((long) radius * 83492791L));

        // Walk the (centerX±radius, centerY±radius) square and sprinkle a
        // realistic-density set of oases. Roughly 1 in 8 tiles is an oasis on
        // a normal Travian map — we copy that ratio so the spread feels right.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx == 0 && dy == 0) continue; // center tile = the scanning village
                if (rng.nextInt(8) != 0) continue;

                int x = centerX + dx;
                int y = centerY + dy;
                int distance = Math.max(Math.abs(dx), Math.abs(dy)); // Chebyshev
                OasisType type = pickType(rng);
                int bonus = pickBonus(rng, type);
                String owner = rng.nextInt(5) == 0
                        ? mockPlayerName(rng)
                        : null;

                OasisTile tile = new OasisTile(scan, x, y, type, bonus, distance, owner);
                scan.addTile(tile);

                switch (type) {
                    case WOOD -> wood++;
                    case CLAY -> clay++;
                    case IRON -> iron++;
                    case CROP -> crop++;
                    case MIXED -> mixed++;
                    default -> { /* UNKNOWN — don't tally */ }
                }
            }
        }

        scan.markComplete(wood, clay, iron, crop, mixed);
        OasisScan saved = scanRepository.save(scan);
        log.info("OasisAnalysisService: mock scan for account {} at ({}, {}, r={}) → "
                        + "{} wood, {} clay, {} iron, {} crop, {} mixed ({} tiles total)",
                accountId, centerX, centerY, radius, wood, clay, iron, crop, mixed,
                wood + clay + iron + crop + mixed);
        return saved;
    }

    /**
     * Cascade hook for account deletion. Kept on this service so the
     * account-delete path doesn't have to know the entity shape directly.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        scanRepository.deleteAllByAccountId(event.accountId());
    }

    private OasisType pickType(Random rng) {
        // Distribution roughly matches a normal Travian map (CROP and MIXED
        // are deliberately less common; CROP is precious because it's
        // hardest to keep a balanced economy without a crop oasis).
        int roll = rng.nextInt(100);
        if (roll < 25) return OasisType.WOOD;
        if (roll < 50) return OasisType.CLAY;
        if (roll < 75) return OasisType.IRON;
        if (roll < 90) return OasisType.CROP;
        return OasisType.MIXED;
    }

    private int pickBonus(Random rng, OasisType type) {
        // Travian's oases come in 25% / 50% bonus tiers. Mixed oases are
        // always 25% (the second resource doubles up on the same tile).
        if (type == OasisType.MIXED) return 25;
        return rng.nextInt(2) == 0 ? 25 : 50;
    }

    private String mockPlayerName(Random rng) {
        String[] roster = { "Achilleas", "Spartacus", "Leonidas", "Hektor", "Perseus",
                "GoldGrabber", "ShieldBro", "FarmKing", "BeastHunter", "OasisLord" };
        return roster[rng.nextInt(roster.length)];
    }
}
