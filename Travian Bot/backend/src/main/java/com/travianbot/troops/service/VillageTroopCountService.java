package com.travianbot.troops.service;

import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.troops.domain.VillageTroopCount;
import com.travianbot.troops.persistence.VillageTroopCountRepository;
import com.travianbot.troops.scraper.VillageTroopsPasteParser;
import com.travianbot.troops.scraper.VillageTroopsPasteParser.ParsedTroopsTable;
import com.travianbot.troops.scraper.VillageTroopsPasteParser.ParsedVillageRow;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read/write surface for {@link VillageTroopCount}. The import path takes a
 * raw paste from {@code Village overview > Troops > Troops in villages},
 * resolves each row to a {@link Village} via name match (and coord fallback
 * when the name is ambiguous), and upserts the snapshot.
 * <p>
 * Rows we can't resolve to a known village are silently skipped — the user
 * may have captured a Natars village we haven't synced yet, and we'd rather
 * import everything else than abort the whole batch. The return envelope
 * tells the caller how many were upserted vs skipped so the UI can surface a
 * "23 imported, 3 skipped" line.
 */
@Service
@Transactional
public class VillageTroopCountService {

    private static final Logger log = LoggerFactory.getLogger(VillageTroopCountService.class);

    /** Pull trailing "X|Y" coords out of "Natars -78|150"-style village names. */
    private static final Pattern TRAILING_COORDS = Pattern.compile(
            "(-?\\d{1,4})\\s*\\|\\s*(-?\\d{1,4})\\s*$"
    );

    private final VillageTroopCountRepository repository;
    private final VillageRepository villageRepository;

    public VillageTroopCountService(VillageTroopCountRepository repository,
                                    VillageRepository villageRepository) {
        this.repository = repository;
        this.villageRepository = villageRepository;
    }

    /**
     * Parse the paste, upsert one {@link VillageTroopCount} per resolvable
     * row. Returns an import summary {@code (imported, skipped, total, tribeId)}.
     */
    public ImportSummary importPaste(Long accountId, String body) {
        if (body == null || body.isBlank()) {
            return new ImportSummary(0, 0, 0, null);
        }
        ParsedTroopsTable parsed = VillageTroopsPasteParser.parse(body);
        if (parsed.rows().isEmpty()) {
            return new ImportSummary(0, 0, 0, parsed.tribeId());
        }

        // Pre-index the account's villages by (lowercased) name and by coords
        // so a row lookup is O(1) instead of N village rows per import line.
        List<Village> villages =
                villageRepository.findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId);
        Map<String, Village> byName = new HashMap<>();
        Map<String, Village> byCoord = new HashMap<>();
        for (Village v : villages) {
            byName.put(v.getName().toLowerCase(), v);
            byCoord.put(coordKey(v.getCoordinateX(), v.getCoordinateY()), v);
        }

        int imported = 0;
        int skipped = 0;
        for (ParsedVillageRow row : parsed.rows()) {
            Village village = resolveVillage(row.villageName(), byName, byCoord);
            if (village == null) {
                log.debug("Troop import: no village matches '{}' on account {} — skipping",
                        row.villageName(), accountId);
                skipped++;
                continue;
            }
            Optional<VillageTroopCount> existing = repository.findFirstByVillageId(village.getId());
            VillageTroopCount snap = existing.orElseGet(
                    () -> new VillageTroopCount(accountId, village.getId()));
            snap.setTribeId(parsed.tribeId());
            snap.setCounts(row.troops(), row.hero());
            repository.save(snap);
            imported++;
        }
        log.info("Troop import for account {}: {} imported, {} skipped (of {} parsed)",
                accountId, imported, skipped, parsed.rows().size());
        return new ImportSummary(imported, skipped, parsed.rows().size(), parsed.tribeId());
    }

    /**
     * Try increasingly-loose strategies to pin a row to a village:
     * exact-name → trailing-coord match (handles "Natars -78|150"-style
     * names whose suffix coords differ from the village's actual name in
     * our DB).
     */
    private Village resolveVillage(String pasteName,
                                   Map<String, Village> byName,
                                   Map<String, Village> byCoord) {
        if (pasteName == null) return null;
        String key = pasteName.toLowerCase().trim();
        Village v = byName.get(key);
        if (v != null) return v;

        Matcher coords = TRAILING_COORDS.matcher(pasteName);
        if (coords.find()) {
            try {
                int x = Integer.parseInt(coords.group(1));
                int y = Integer.parseInt(coords.group(2));
                Village byPos = byCoord.get(coordKey(x, y));
                if (byPos != null) return byPos;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static String coordKey(int x, int y) {
        return x + "|" + y;
    }

    /** All snapshots for the account — list endpoint. */
    @Transactional(readOnly = true)
    public List<VillageTroopCount> listFor(Long accountId) {
        return repository.findAllByAccountId(accountId);
    }

    /** Wipe all snapshots — useful for testing or starting fresh. */
    public int clearFor(Long accountId) {
        List<VillageTroopCount> rows = repository.findAllByAccountId(accountId);
        int n = rows.size();
        if (n > 0) repository.deleteAllByAccountId(accountId);
        return n;
    }

    /**
     * Cleanup hook — wipe this account's troop snapshots when the account
     * itself is deleted. Same pattern as the incoming-attacks listener.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }

    /** Wire shape for the import endpoint. */
    public record ImportSummary(int imported, int skipped, int parsed, Integer tribeId) {
    }
}
