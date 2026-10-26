package com.travianbot.attackplan.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.attackplan.domain.AttackPlan;
import com.travianbot.attackplan.domain.AttackPlanSource;
import com.travianbot.attackplan.domain.AttackPlanTarget;
import com.travianbot.attackplan.persistence.AttackPlanRepository;
import com.travianbot.server.domain.Server;
import com.travianbot.server.persistence.ServerRepository;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.VillageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CRUD + matrix-compute surface for {@link AttackPlan}.
 * <p>
 * The {@code save*Plan} family takes plain value-object inputs (lists of
 * {@link TargetInput} / source village ids) and rewrites the plan's children
 * atomically — we don't expose patch-one-child endpoints because the UI
 * always submits the whole plan after the user clicks Save. That keeps the
 * server's transaction radius tight and the API surface small.
 * <p>
 * Matrix compute is read-only. Distance is Euclidean (matching
 * {@code IncomingAttack.recompute}); the effective speed is
 * {@code plan.speedFieldsPerHour × server.speedMultiplier}. The server
 * multiplier is resolved through the owning account's {@code serverUrl} —
 * unknown server → multiplier 1 (the matrix still computes; the user just
 * gets x1 numbers).
 */
@Service
@Transactional
public class AttackPlanService {

    private static final Logger log = LoggerFactory.getLogger(AttackPlanService.class);

    private final AttackPlanRepository repository;
    private final VillageRepository villageRepository;
    private final AccountRepository accountRepository;
    private final ServerRepository serverRepository;

    public AttackPlanService(
            AttackPlanRepository repository,
            VillageRepository villageRepository,
            AccountRepository accountRepository,
            ServerRepository serverRepository
    ) {
        this.repository = repository;
        this.villageRepository = villageRepository;
        this.accountRepository = accountRepository;
        this.serverRepository = serverRepository;
    }

    // ---- CRUD ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AttackPlan> listFor(Long accountId) {
        return repository.findAllByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public AttackPlan get(Long accountId, Long planId) {
        return repository.findByIdAndAccountId(planId, accountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Attack plan not found: " + planId));
    }

    public AttackPlan create(Long accountId, String name, Double speedFieldsPerHour,
                             Instant landingAt, List<TargetInput> targets,
                             List<Long> sourceVillageIds) {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        AttackPlan plan = new AttackPlan(accountId, name.trim());
        if (speedFieldsPerHour != null && speedFieldsPerHour > 0) {
            plan.setSpeedFieldsPerHour(speedFieldsPerHour);
        }
        plan.setLandingAt(landingAt);
        applyTargets(plan, targets);
        applySources(plan, accountId, sourceVillageIds);
        return repository.save(plan);
    }

    public AttackPlan update(Long accountId, Long planId, String name,
                             Double speedFieldsPerHour, Instant landingAt,
                             List<TargetInput> targets, List<Long> sourceVillageIds) {
        AttackPlan plan = get(accountId, planId);
        if (name != null && !name.isBlank()) plan.setName(name.trim());
        if (speedFieldsPerHour != null && speedFieldsPerHour > 0) {
            plan.setSpeedFieldsPerHour(speedFieldsPerHour);
        }
        plan.setLandingAt(landingAt);
        applyTargets(plan, targets);
        applySources(plan, accountId, sourceVillageIds);
        plan.touch();
        return repository.save(plan);
    }

    public void delete(Long accountId, Long planId) {
        AttackPlan plan = get(accountId, planId);
        repository.delete(plan);
    }

    /**
     * Wipe the plan's targets and rebuild from the supplied list. Cheaper +
     * simpler than a per-row diff, and the table is small (rarely more than
     * a couple of dozen targets per plan).
     */
    private void applyTargets(AttackPlan plan, List<TargetInput> targets) {
        plan.getTargets().clear();
        if (targets == null) return;
        int idx = 0;
        for (TargetInput t : targets) {
            if (t == null) continue;
            String label = t.label() == null || t.label().isBlank() ? null : t.label().trim();
            plan.getTargets().add(new AttackPlanTarget(plan, t.x(), t.y(), label, idx++));
        }
    }

    /**
     * Same idea as {@link #applyTargets}: drop and rebuild. Validates each
     * village id actually belongs to the account so a misconfigured UI can't
     * inject foreign villages into a plan.
     */
    private void applySources(AttackPlan plan, Long accountId, List<Long> sourceVillageIds) {
        plan.getSources().clear();
        if (sourceVillageIds == null || sourceVillageIds.isEmpty()) return;
        Set<Long> ownedIds = villageRepository
                .findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId)
                .stream().map(Village::getId).collect(Collectors.toSet());
        int idx = 0;
        Set<Long> seen = new HashSet<>();
        for (Long vid : sourceVillageIds) {
            if (vid == null || !ownedIds.contains(vid) || !seen.add(vid)) continue;
            plan.getSources().add(new AttackPlanSource(plan, vid, idx++));
        }
    }

    // ---- Matrix ----------------------------------------------------------

    @Transactional(readOnly = true)
    public MatrixResult computeMatrix(Long accountId, Long planId) {
        AttackPlan plan = get(accountId, planId);
        Account account = accountRepository.findById(accountId).orElseThrow(() ->
                new IllegalStateException("account not found: " + accountId));

        int speedMultiplier = resolveSpeedMultiplier(account);
        double effectiveSpeed = plan.getSpeedFieldsPerHour() * (double) speedMultiplier;
        if (effectiveSpeed <= 0) {
            // Shouldn't happen — speed is required and validated — but guard
            // against division-by-zero rather than throw, so the matrix
            // endpoint never 500s on bad data.
            log.warn("Plan {} effective speed is 0 (base={}, mult={}) — returning empty matrix",
                    plan.getId(), plan.getSpeedFieldsPerHour(), speedMultiplier);
            return new MatrixResult(plan, account, speedMultiplier, effectiveSpeed,
                    List.of(), List.of(), List.of());
        }

        List<AttackPlanTarget> targets = plan.getTargets();
        List<AttackPlanSource> sources = plan.getSources();

        Map<Long, Village> villagesById = villageRepository
                .findAllByAccountIdOrderByOrderIndexAscCreatedAtAsc(accountId)
                .stream().collect(Collectors.toMap(Village::getId, v -> v));

        // Resolve each source row -> its Village. Sources that no longer point
        // at an owned village (village deleted since the plan was saved) get
        // dropped from the matrix instead of crashing — the user can clean
        // them up on the next save.
        List<SourceRow> sourceRows = new ArrayList<>();
        for (AttackPlanSource s : sources) {
            Village v = villagesById.get(s.getVillageId());
            if (v == null) {
                log.debug("Plan {} source villageId={} no longer owned — skipping in matrix",
                        plan.getId(), s.getVillageId());
                continue;
            }
            sourceRows.add(new SourceRow(s.getVillageId(), v.getName(),
                    v.getCoordinateX(), v.getCoordinateY()));
        }

        List<TargetRow> targetRows = new ArrayList<>();
        for (AttackPlanTarget t : targets) {
            targetRows.add(new TargetRow(t.getId(), t.getX(), t.getY(), t.getLabel()));
        }

        Instant now = Instant.now();
        // Build a mutable cell grid first so we can stamp sendOrder in a
        // second pass per column. Using a primitive 2D array would be neater
        // memory-wise but the matrix is small (sources×targets rarely > 100)
        // and the readability win of plain Lists is worth it.
        List<List<MutableCell>> rows = new ArrayList<>();
        for (SourceRow src : sourceRows) {
            List<MutableCell> row = new ArrayList<>(targetRows.size());
            for (TargetRow tgt : targetRows) {
                double dx = src.x() - tgt.x();
                double dy = src.y() - tgt.y();
                double distance = Math.sqrt(dx * dx + dy * dy);
                double travelHours = distance / effectiveSpeed;
                long travelSeconds = Math.round(travelHours * 3600.0);
                Instant launchAt = null;
                Long lateBySeconds = null;
                if (plan.getLandingAt() != null) {
                    launchAt = plan.getLandingAt().minusSeconds(travelSeconds);
                    lateBySeconds = Duration.between(launchAt, now).getSeconds();
                }
                row.add(new MutableCell(distance, travelSeconds, launchAt, lateBySeconds, null));
            }
            rows.add(row);
        }

        // ---- Wave-order assignment ----
        // For each target column, sort the source cells by launchAt ASC and
        // stamp sendOrder = 1..N. This is the heart of the "AI" the user
        // asked for: when N attacks all need to LAND at the same instant,
        // Travian sequences them by SEND order — so wave 1 is the source
        // that has the LONGEST travel (and therefore the EARLIEST launch
        // time). The coordinator reads off the schedule top-to-bottom and
        // knows exactly who pushes the button first.
        //
        // Skip entirely when landingAt is null — without it launchAt is null
        // and the order is undefined; the UI then renders cells without the
        // wave badge.
        if (plan.getLandingAt() != null && !targetRows.isEmpty() && !sourceRows.isEmpty()) {
            for (int col = 0; col < targetRows.size(); col++) {
                final int finalCol = col;
                List<Integer> sourceIndices = new ArrayList<>(sourceRows.size());
                for (int i = 0; i < sourceRows.size(); i++) sourceIndices.add(i);
                sourceIndices.sort((a, b) -> {
                    Instant la = rows.get(a).get(finalCol).launchAt;
                    Instant lb = rows.get(b).get(finalCol).launchAt;
                    if (la == null && lb == null) return 0;
                    if (la == null) return 1;
                    if (lb == null) return -1;
                    return la.compareTo(lb);
                });
                int order = 1;
                for (int srcIdx : sourceIndices) {
                    MutableCell c = rows.get(srcIdx).get(finalCol);
                    if (c.launchAt != null) c.sendOrder = order++;
                }
            }
        }

        // Freeze the mutable grid into the immutable Cell records the DTO uses.
        List<List<Cell>> frozenRows = new ArrayList<>(rows.size());
        for (List<MutableCell> r : rows) {
            List<Cell> out = new ArrayList<>(r.size());
            for (MutableCell c : r) {
                out.add(new Cell(c.distance, c.travelSeconds, c.launchAt, c.lateBySeconds, c.sendOrder));
            }
            frozenRows.add(out);
        }

        return new MatrixResult(plan, account, speedMultiplier, effectiveSpeed,
                sourceRows, targetRows, frozenRows);
    }

    /**
     * Mutable scratch holder used only during matrix construction so we can
     * stamp {@code sendOrder} in a second pass without rebuilding the row.
     * Immediately frozen into a {@link Cell} record on the way out.
     */
    private static final class MutableCell {
        final double distance;
        final long travelSeconds;
        final Instant launchAt;
        final Long lateBySeconds;
        Integer sendOrder;

        MutableCell(double distance, long travelSeconds, Instant launchAt,
                    Long lateBySeconds, Integer sendOrder) {
            this.distance = distance;
            this.travelSeconds = travelSeconds;
            this.launchAt = launchAt;
            this.lateBySeconds = lateBySeconds;
            this.sendOrder = sendOrder;
        }
    }

    private int resolveSpeedMultiplier(Account account) {
        if (account == null || account.getServerUrl() == null) return 1;
        String canonical = Server.canonicalUrl(account.getServerUrl());
        Optional<Server> serverRow = serverRepository.findByUrl(canonical);
        if (serverRow.isEmpty()) return 1;
        Integer m = serverRow.get().getSpeedMultiplier();
        return (m == null || m <= 0) ? 1 : m;
    }

    // ---- Account-deletion cleanup ---------------------------------------

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }

    // ---- Plain value-object inputs / results ----------------------------

    public record TargetInput(int x, int y, String label) {}

    public record SourceRow(Long villageId, String villageName, int x, int y) {}

    public record TargetRow(Long targetId, int x, int y, String label) {}

    /**
     * One matrix cell. {@link #launchAt}, {@link #lateBySeconds} and
     * {@link #sendOrder} are null when the plan has no {@code landingAt} —
     * the UI then just shows travel time alone.
     * <p>
     * {@link #sendOrder} is the 1-based wave number for this source within
     * its target column (1 = first to send → first to land on shared landing
     * timestamp). Computed by sorting the column's cells by launchAt ASC.
     */
    public record Cell(double distance, long travelSeconds,
                       Instant launchAt, Long lateBySeconds,
                       Integer sendOrder) {}

    public record MatrixResult(AttackPlan plan, Account account,
                               int serverSpeedMultiplier, double effectiveSpeed,
                               List<SourceRow> sources, List<TargetRow> targets,
                               List<List<Cell>> rows) {}
}
