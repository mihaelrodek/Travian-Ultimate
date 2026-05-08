package com.travianbot.village.service;

import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps {@link BuildingQueueItem} rows aligned with what the scraper most recently
 * saw. Called after each per-village scrape so the queue widget and the auto-start
 * scheduler agree on which of our RUNNING rows are still actually in-flight.
 *
 * <h3>Transition rules (RUNNING → ?)</h3>
 * <ul>
 *   <li>scraped level &ge; {@code targetLevel} → {@link QueueStatus#DONE}. The game
 *       finished the upgrade; we noticed on the next scrape.</li>
 *   <li>not under construction AND level still below target → {@link QueueStatus#PENDING}.
 *       Either our {@link VillageService#startQueueItem start click} was rejected, the
 *       build was cancelled in-game, or we optimistically marked RUNNING before the
 *       server actually accepted it. We let the scheduler retry next tick instead of
 *       leaving it RUNNING and burning a capacity slot.</li>
 *   <li>still under construction → stay RUNNING.</li>
 *   <li>slot not yet scraped (no matching row) → stay RUNNING. Can't decide either way,
 *       and the next scrape will clear up ambiguity.</li>
 * </ul>
 *
 * <p>Slot-less queue rows are skipped — with no slot there's nothing in the world to
 * compare against, and those rows can't have been "started" anyway (startQueueItem
 * rejects them).
 */
@Service
@Transactional
public class QueueReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(QueueReconciliationService.class);

    private final VillageRepository villageRepository;
    private final ResourceFieldRepository resourceFieldRepository;
    private final BuildingRepository buildingRepository;

    public QueueReconciliationService(
            VillageRepository villageRepository,
            ResourceFieldRepository resourceFieldRepository,
            BuildingRepository buildingRepository
    ) {
        this.villageRepository = villageRepository;
        this.resourceFieldRepository = resourceFieldRepository;
        this.buildingRepository = buildingRepository;
    }

    /**
     * Reconcile every RUNNING queue row on the village against the latest scrape.
     * Safe to call as often as you like; a no-op when nothing has changed.
     *
     * @return counts for logging / tests — how many rows moved to DONE, how many
     *         were reset to PENDING.
     */
    public ReconcileResult reconcile(Long villageId) {
        Village village = villageRepository.findById(villageId)
                .orElseThrow(() -> new VillageNotFoundException(villageId));

        // Index scraped state by slot so the per-item walk is O(1) per lookup.
        Map<Integer, ResourceField> fieldsBySlot = new HashMap<>();
        for (ResourceField rf : resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(villageId)) {
            fieldsBySlot.put(rf.getSlotId(), rf);
        }
        Map<Integer, Building> buildingsBySlot = new HashMap<>();
        for (Building b : buildingRepository.findAllByVillageIdOrderBySlotIdAsc(villageId)) {
            buildingsBySlot.put(b.getSlotId(), b);
        }

        int completed = 0;
        int reverted = 0;
        for (BuildingQueueItem item : village.getQueue()) {
            if (item.getStatus() != QueueStatus.RUNNING) continue;
            Integer slot = item.getSlot();
            if (slot == null) continue; // nothing in the world to compare against

            Integer currentLevel = null;
            Boolean underConstruction = null;
            if (BuildQueueCapacity.SlotKind.forSlot(slot) == BuildQueueCapacity.SlotKind.FIELD) {
                ResourceField rf = fieldsBySlot.get(slot);
                if (rf != null) {
                    currentLevel = rf.getLevel();
                    underConstruction = rf.isUnderConstruction();
                }
            } else {
                Building b = buildingsBySlot.get(slot);
                if (b != null) {
                    currentLevel = b.getLevel();
                    underConstruction = b.isUnderConstruction();
                }
            }

            if (currentLevel == null) {
                // Slot hasn't been scraped yet — keep RUNNING and try again next tick.
                continue;
            }

            if (currentLevel >= item.getTargetLevel()) {
                item.markStatus(QueueStatus.DONE,
                        "Reconciled: slot " + slot + " reached level " + currentLevel);
                completed++;
            } else if (Boolean.FALSE.equals(underConstruction)) {
                // Not being built and not at target — the game dropped it. Put it back
                // in the pending pool so the scheduler can re-pick it on the next tick.
                item.resetToPending(
                        "Reconciled: slot " + slot + " not under construction (level "
                                + currentLevel + " < target " + item.getTargetLevel() + ")");
                reverted++;
            }
            // else: still under construction — leave the row RUNNING.
        }

        if (completed > 0 || reverted > 0) {
            log.debug("Reconciled queue for village {}: {} completed, {} reverted to pending",
                    villageId, completed, reverted);
        }
        return new ReconcileResult(completed, reverted);
    }

    public record ReconcileResult(int completed, int reverted) {
        public int total() { return completed + reverted; }
    }
}
