package com.travianbot.village.service;

import com.travianbot.account.domain.Tribe;
import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.QueueStatus;
import com.travianbot.village.domain.ResourceField;

import java.util.Collection;
import java.util.List;

/**
 * Translates Travian's per-village concurrent-build rules into a go/no-go decision
 * for "can I start a new upgrade on slot X right now". Lives outside
 * {@link VillageService} so the rules are unit-testable without a Spring context
 * and so the scheduler can call them without reaching into the service's
 * transaction semantics.
 *
 * <h3>Rule set</h3>
 *
 * The in-game queue has room only when <em>both</em> of the following are true
 * of the village:
 * <ol>
 *   <li>the total number of builds in progress is below the tribe's slot cap
 *       ({@link #totalSlots}), and</li>
 *   <li>the specific kind we want to add (field vs. village-building) fits the
 *       composition rule for the tribe.</li>
 * </ol>
 *
 * <h4>Non-Roman tribes</h4>
 * A non-Roman village can build <strong>1</strong> upgrade at a time
 * (<strong>2</strong> with Travian Plus), BUT the concurrent upgrades must be the
 * <em>same kind</em> — either both fields, or both buildings, never mixed.
 *
 * <h4>Romans</h4>
 * Romans get <strong>1 building + 1 field</strong> concurrently as a baseline.
 * Travian Plus adds <strong>one more slot of either kind</strong>, so Plus
 * Romans can run 1B + 2F or 2B + 1F (never 3 of the same kind).
 *
 * <h3>Source of truth</h3>
 * The scheduler feeds us two things: (1) the number of in-progress upgrades on
 * actual buildings/fields (scraped {@code underConstruction} flags), and
 * (2) our own {@link QueueStatus#RUNNING} rows in {@link BuildingQueueItem}.
 * Because we use a "trust DB, rescrape on mismatch" policy, normally those two
 * numbers agree — but if they diverge we use the <em>max</em> to avoid starting
 * a build the game would just reject. The extra pessimism is cheap compared to
 * racing the game's rules.
 */
public final class BuildQueueCapacity {

    private BuildQueueCapacity() {
    }

    public enum SlotKind {
        /** Village-building: dorf2 slots 19..40. */
        BUILDING,
        /** Resource field: dorf1 slots 1..18. */
        FIELD;

        public static SlotKind forSlot(int slot) {
            return slot <= 18 ? FIELD : BUILDING;
        }
    }

    /**
     * Live per-village counts — both scraped and DB-derived — used by
     * {@link #canStart}. Built by the scheduler for the village it's considering.
     * <p>
     * "running" means a build that's currently consuming one of the in-game
     * slots. We count it separately for fields and buildings because the Roman
     * composition rule cares which kind they are.
     */
    public record VillageQueueState(
            int scrapedFieldsUnderConstruction,
            int scrapedBuildingsUnderConstruction,
            int dbRunningFieldRows,
            int dbRunningBuildingRows
    ) {
        /** Pessimistic count of field slots currently occupied. */
        public int occupiedFields() {
            return Math.max(scrapedFieldsUnderConstruction, dbRunningFieldRows);
        }

        /** Pessimistic count of building slots currently occupied. */
        public int occupiedBuildings() {
            return Math.max(scrapedBuildingsUnderConstruction, dbRunningBuildingRows);
        }

        public int totalOccupied() {
            return occupiedFields() + occupiedBuildings();
        }
    }

    /**
     * @return true if the village can accept a new build of {@code kind} right now.
     */
    public static boolean canStart(Tribe tribe, boolean plusActive, SlotKind kind, VillageQueueState state) {
        int total = totalSlots(tribe, plusActive);
        if (state.totalOccupied() >= total) return false;

        if (tribe == Tribe.ROMANS) {
            // Each kind has a baseline of 1 slot. Plus adds 1 flex slot usable
            // by either kind. So a Roman can add a new field as long as:
            //   occupiedFields < 1 + (plus && occupiedBuildings < 1 ? 1 : 1)
            // — which simplifies to occupiedFields + occupiedBuildings < total.
            // We've already checked the total cap above, so Romans can always
            // add a slot of either kind until the cap is hit.
            return true;
        }

        // Non-Romans: both concurrent slots must be the same kind. That means we
        // refuse to add a FIELD if any BUILDING is running (and vice-versa), no
        // matter how many total slots we have.
        if (kind == SlotKind.FIELD) {
            return state.occupiedBuildings() == 0;
        } else {
            return state.occupiedFields() == 0;
        }
    }

    /**
     * Total concurrent-build cap for this tribe/plus combination.
     *   Non-Roman, no plus: 1
     *   Non-Roman, plus:    2
     *   Roman, no plus:     2   (1B + 1F)
     *   Roman, plus:        3   (1B + 1F + 1 flex)
     */
    public static int totalSlots(Tribe tribe, boolean plusActive) {
        if (tribe == null) {
            // Unknown tribe → fall back to the most restrictive rule (1 slot, no plus).
            // Better to under-use capacity than to race against a rule we can't verify.
            return 1;
        }
        if (tribe == Tribe.ROMANS) {
            return plusActive ? 3 : 2;
        }
        return plusActive ? 2 : 1;
    }

    /**
     * Convenience: count how many of this village's queue rows are currently RUNNING,
     * split by slot kind. Rows without a resolved slot fall through to BUILDING
     * because slot-less queue rows can't target a resource field anyway.
     */
    public static RunningCounts countRunning(Collection<BuildingQueueItem> queue) {
        int fields = 0, buildings = 0;
        for (BuildingQueueItem it : queue) {
            if (it.getStatus() != QueueStatus.RUNNING) continue;
            Integer slot = it.getSlot();
            SlotKind kind = slot == null ? SlotKind.BUILDING : SlotKind.forSlot(slot);
            if (kind == SlotKind.FIELD) fields++;
            else buildings++;
        }
        return new RunningCounts(fields, buildings);
    }

    public record RunningCounts(int fields, int buildings) {
        public int total() { return fields + buildings; }
    }

    /**
     * Convenience: count how many of this village's fields/buildings are under
     * construction right now according to the most recent scrape. This is the
     * ground truth — if it disagrees with DB {@link QueueStatus#RUNNING} rows,
     * the scrape wins.
     * <p>
     * Village owns its queue but not the field/building collections (they're in
     * their own repositories), so the scheduler fetches both lists and passes
     * them in. Accepting collections keeps this helper free of repository deps.
     */
    public static ScrapedCounts countUnderConstruction(List<ResourceField> fields, List<Building> buildings) {
        int f = 0, b = 0;
        if (fields != null) {
            for (ResourceField rf : fields) {
                if (rf.isUnderConstruction()) f++;
            }
        }
        if (buildings != null) {
            for (Building bl : buildings) {
                if (bl.isUnderConstruction()) b++;
            }
        }
        return new ScrapedCounts(f, b);
    }

    public record ScrapedCounts(int fields, int buildings) {
        public int total() { return fields + buildings; }
    }
}
