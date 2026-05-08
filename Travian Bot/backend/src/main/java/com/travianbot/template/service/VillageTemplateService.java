package com.travianbot.template.service;

import com.travianbot.account.domain.Account;
import com.travianbot.account.event.AccountDeletingEvent;
import com.travianbot.account.persistence.AccountRepository;
import com.travianbot.account.service.AccountNotFoundException;
import com.travianbot.template.api.dto.TemplateStepDto;
import com.travianbot.template.api.dto.VillageTemplateRequest;
import com.travianbot.template.domain.TemplateCategory;
import com.travianbot.template.domain.TemplateStep;
import com.travianbot.template.domain.VillageTemplate;
import com.travianbot.template.persistence.VillageTemplateRepository;
import com.travianbot.village.domain.Building;
import com.travianbot.village.domain.BuildingQueueItem;
import com.travianbot.village.domain.ResourceField;
import com.travianbot.village.domain.ResourceType;
import com.travianbot.village.domain.Village;
import com.travianbot.village.persistence.BuildingQueueItemRepository;
import com.travianbot.village.persistence.BuildingRepository;
import com.travianbot.village.persistence.ResourceFieldRepository;
import com.travianbot.village.persistence.VillageRepository;
import com.travianbot.village.service.VillageNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRUD + CSV import/export + "apply to village" for {@link VillageTemplate}.
 * <p>
 * Templates are account-scoped plans; applying one to a village appends rows
 * to that village's build queue. We deliberately append rather than overwrite
 * — a user may have in-flight work in the queue that they don't want to lose,
 * and undo-ing an apply is as simple as trimming the bottom of the queue in
 * the Villages screen.
 * <p>
 * CSV format is intentionally minimal — one line per step, columns in order:
 * {@code buildingType,slot,targetLevel} where {@code slot} can be blank. No
 * header row. Chosen to match what TravBotSharp users are already used to
 * pasting into the app, so existing libraries of plans carry over without
 * hand-editing.
 */
@Service
@Transactional
public class VillageTemplateService {

    private static final Logger log = LoggerFactory.getLogger(VillageTemplateService.class);

    private final VillageTemplateRepository repository;
    private final AccountRepository accountRepository;
    private final VillageRepository villageRepository;
    private final BuildingQueueItemRepository queueItemRepository;
    private final ResourceFieldRepository resourceFieldRepository;
    private final BuildingRepository buildingRepository;

    public VillageTemplateService(
            VillageTemplateRepository repository,
            AccountRepository accountRepository,
            VillageRepository villageRepository,
            BuildingQueueItemRepository queueItemRepository,
            ResourceFieldRepository resourceFieldRepository,
            BuildingRepository buildingRepository
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.villageRepository = villageRepository;
        this.queueItemRepository = queueItemRepository;
        this.resourceFieldRepository = resourceFieldRepository;
        this.buildingRepository = buildingRepository;
    }

    @Transactional(readOnly = true)
    public List<VillageTemplate> list(Long accountId) {
        if (accountId == null) {
            // Templates are always account-scoped; refusing the global list
            // keeps the admin-ish "show me everything" path off-limits.
            return List.of();
        }
        return repository.findAllByAccountIdOrderByUpdatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public VillageTemplate get(Long id) {
        return repository.findById(id).orElseThrow(() -> new VillageTemplateNotFoundException(id));
    }

    public VillageTemplate create(VillageTemplateRequest req) {
        if (req.accountId() == null) {
            throw new IllegalArgumentException("accountId is required to create a template");
        }
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new AccountNotFoundException(req.accountId()));
        VillageTemplate template = new VillageTemplate(
                account,
                trimOrEmpty(req.name()),
                req.description(),
                req.category()
        );
        applySteps(template, req.steps());
        return repository.save(template);
    }

    public VillageTemplate update(Long id, VillageTemplateRequest req) {
        VillageTemplate template = get(id);
        template.update(trimOrEmpty(req.name()), req.description(), req.category());
        applySteps(template, req.steps());
        template.touch();
        return repository.save(template);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) throw new VillageTemplateNotFoundException(id);
        repository.deleteById(id);
    }

    /**
     * Append the template's steps onto a village's build queue.
     * <p>
     * Steps land at the END of the queue — existing items keep their order
     * and status. New rows get fresh {@code orderIndex} values computed from
     * the highest existing index, so the template's own internal order is
     * preserved.
     *
     * <h3>Macro expansion</h3>
     * Steps whose {@code buildingType} is a macro (ALL_WOOD / ALL_CLAY /
     * ALL_IRON / ALL_CROP / ALL_RESOURCES / ALL_RESOURCES_NO_CROP) expand
     * into one queue row per matching resource field — that's what makes the
     * "upgrade every wood field to L8" template fit on a single row in the
     * editor.
     *
     * <h3>Slot auto-assignment for infrastructure</h3>
     * Templates no longer carry slots — the user designs them once and they
     * apply to whichever village is in front of them. At apply time we
     * resolve a slot per step:
     *   1. If a building of this type already exists in the village, reuse
     *      its slot (further upgrades stack on the same building).
     *   2. Otherwise, take the first empty slot in 19..40 that hasn't been
     *      claimed by an earlier step in this same apply pass. Empty
     *      slots claimed in step 1/2 are tracked locally so two new
     *      buildings of different types don't collide.
     * If no empty slot is left we leave the row with a null slot — the
     * scheduler will skip it and the user can resolve it manually.
     */
    public int applyToVillage(Long templateId, Long villageId) {
        VillageTemplate template = get(templateId);
        Village village = villageRepository.findById(villageId)
                .orElseThrow(() -> new VillageNotFoundException(villageId));
        if (!village.getAccount().getId().equals(template.getAccount().getId())) {
            // Refusing cross-account application matches the account-scoping
            // invariant. A template for one server's tribe rules wouldn't
            // make sense on another account anyway.
            throw new IllegalArgumentException(
                    "Template " + templateId + " belongs to a different account than village " + villageId + ".");
        }

        List<ResourceField> fields = resourceFieldRepository.findAllByVillageIdOrderBySlotIdAsc(villageId);
        List<Building> buildings = buildingRepository.findAllByVillageIdOrderBySlotIdAsc(villageId);
        List<BuildingQueueItem> existing =
                queueItemRepository.findAllByVillageIdOrderByOrderIndexAscIdAsc(villageId);

        // Slots already occupied or pending from earlier in this apply pass.
        // Initialised from the village's current buildings + existing queue
        // rows so we don't double-book; new claims add to it as we expand
        // template steps.
        Set<Integer> reservedSlots = new HashSet<>();
        for (Building b : buildings) {
            if (b.getGid() != 0) reservedSlots.add(b.getSlotId());
        }
        for (BuildingQueueItem qi : existing) {
            if (qi.getSlot() != null) reservedSlots.add(qi.getSlot());
        }
        // Slots-by-type for "this building already exists or is queued at
        // slot N" lookup. Built lazily as we go so multiple template steps
        // for the same type land in the same slot.
        Map<String, Integer> slotByType = new HashMap<>();
        for (Building b : buildings) {
            if (b.getGid() == 0 || b.getName() == null) continue;
            slotByType.putIfAbsent(buildingTypeKey(b.getName()), b.getSlotId());
        }
        for (BuildingQueueItem qi : existing) {
            if (qi.getSlot() == null) continue;
            slotByType.putIfAbsent(qi.getBuildingType(), qi.getSlot());
        }

        int nextOrder = existing.stream().mapToInt(BuildingQueueItem::getOrderIndex).max().orElse(-1) + 1;

        List<BuildingQueueItem> created = new ArrayList<>();
        for (TemplateStep step : template.getSteps()) {
            String type = step.getBuildingType();
            int targetLevel = step.getTargetLevel();
            List<int[]> expansions = expandMacro(type, fields);
            if (expansions != null) {
                // Macro: one queue row per matching resource field, all to
                // the same target level. Slot is the field's slotId; the
                // building type maps from the field's resource type.
                for (int[] pair : expansions) {
                    int slot = pair[0];
                    String concreteType = resourceBuildingTypeForGid(pair[1]);
                    created.add(new BuildingQueueItem(village, concreteType, slot, targetLevel, nextOrder++));
                    reservedSlots.add(slot);
                }
                continue;
            }

            Integer slot = step.getSlot();
            if (slot == null) {
                // Reuse an existing slot (this building already lives somewhere
                // in the village or earlier in the queue) before claiming a new
                // empty one — keeps upgrade-chains stacked.
                Integer existingSlot = slotByType.get(type);
                if (existingSlot != null) {
                    slot = existingSlot;
                } else if (isResourceBuildingType(type)) {
                    // A bare WOODCUTTER / CLAY_PIT / IRON_MINE / CROPLAND with
                    // no slot — pick the first matching resource field that
                    // hasn't been claimed in this pass.
                    slot = firstFieldSlot(fields, type, reservedSlots);
                } else {
                    slot = firstEmptyInfraSlot(reservedSlots);
                }
            }
            if (slot != null) {
                reservedSlots.add(slot);
                slotByType.putIfAbsent(type, slot);
            }
            created.add(new BuildingQueueItem(village, type, slot, targetLevel, nextOrder++));
        }
        // Collection-level save: the queue is owned by the village in
        // VillageService terms, but here we cross module boundaries and just
        // need rows persisted. orphanRemoval is irrelevant for the append
        // case.
        queueItemRepository.saveAll(created);

        log.info("Applied template {} to village {}: added {} queue items",
                templateId, villageId, created.size());
        return created.size();
    }

    /**
     * If {@code type} is a macro, return the list of (slotId, gid) pairs to
     * expand into; otherwise null. Each pair becomes one queue row at the
     * step's target level.
     */
    private List<int[]> expandMacro(String type, List<ResourceField> fields) {
        return switch (type) {
            case "ALL_WOOD" -> matchingFields(fields, ResourceType.WOOD);
            case "ALL_CLAY" -> matchingFields(fields, ResourceType.CLAY);
            case "ALL_IRON" -> matchingFields(fields, ResourceType.IRON);
            case "ALL_CROP" -> matchingFields(fields, ResourceType.CROP);
            case "ALL_RESOURCES" -> {
                List<int[]> out = new ArrayList<>();
                for (ResourceField f : fields) {
                    out.add(new int[] { f.getSlotId(), gidForType(f.getType()) });
                }
                yield out;
            }
            case "ALL_RESOURCES_NO_CROP" -> {
                List<int[]> out = new ArrayList<>();
                for (ResourceField f : fields) {
                    if (f.getType() == ResourceType.CROP) continue;
                    out.add(new int[] { f.getSlotId(), gidForType(f.getType()) });
                }
                yield out;
            }
            default -> null;
        };
    }

    private static List<int[]> matchingFields(List<ResourceField> fields, ResourceType wanted) {
        List<int[]> out = new ArrayList<>();
        for (ResourceField f : fields) {
            if (f.getType() == wanted) out.add(new int[] { f.getSlotId(), gidForType(wanted) });
        }
        return out;
    }

    private static int gidForType(ResourceType t) {
        return switch (t) {
            case WOOD -> 1;
            case CLAY -> 2;
            case IRON -> 3;
            case CROP -> 4;
        };
    }

    private static String resourceBuildingTypeForGid(int gid) {
        return switch (gid) {
            case 1 -> "WOODCUTTER";
            case 2 -> "CLAY_PIT";
            case 3 -> "IRON_MINE";
            case 4 -> "CROPLAND";
            default -> throw new IllegalStateException("Unknown resource gid " + gid);
        };
    }

    private static boolean isResourceBuildingType(String type) {
        return "WOODCUTTER".equals(type) || "CLAY_PIT".equals(type)
                || "IRON_MINE".equals(type) || "CROPLAND".equals(type);
    }

    private static Integer firstFieldSlot(List<ResourceField> fields, String type, Set<Integer> reserved) {
        ResourceType wanted = switch (type) {
            case "WOODCUTTER" -> ResourceType.WOOD;
            case "CLAY_PIT" -> ResourceType.CLAY;
            case "IRON_MINE" -> ResourceType.IRON;
            case "CROPLAND" -> ResourceType.CROP;
            default -> null;
        };
        if (wanted == null) return null;
        for (ResourceField f : fields) {
            if (f.getType() == wanted && !reserved.contains(f.getSlotId())) return f.getSlotId();
        }
        return null;
    }

    private static Integer firstEmptyInfraSlot(Set<Integer> reserved) {
        for (int s = 19; s <= 40; s++) {
            if (!reserved.contains(s)) return s;
        }
        return null;
    }

    /**
     * Best-effort: turn a scraped building name like "Main Building" into the
     * canonical {@code MAIN_BUILDING} key the queue stores. The scraper writes
     * names verbatim from the in-game DOM; we uppercase and underscore-join
     * the same way the frontend does so type lookups round-trip.
     */
    private static String buildingTypeKey(String name) {
        return name.trim().toUpperCase().replaceAll("\\s+", "_");
    }

    /**
     * Parse a CSV body into a brand-new template. Each non-blank line is one
     * step — header lines and comments aren't supported to keep the parser
     * permissive on content copied out of spreadsheets.
     * <p>
     * Columns: {@code buildingType, slot, targetLevel}. Blank slot columns
     * yield a null slot (so "Main Building,,20" is valid).
     *
     * @throws IllegalArgumentException on any malformed line, with the line
     *         number so the user can find the offender in the paste box.
     */
    public VillageTemplate importCsv(Long accountId, String name, String description,
                                     TemplateCategory category, String csv) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required to import a template");
        }
        List<TemplateStepDto> parsed = parseCsv(csv);
        VillageTemplateRequest req = new VillageTemplateRequest(
                accountId,
                name,
                description,
                category,
                parsed
        );
        return create(req);
    }

    /**
     * Render the current steps of a template as CSV — exactly the format
     * {@link #importCsv} accepts, so export ↔ import is lossless.
     */
    @Transactional(readOnly = true)
    public String exportCsv(Long templateId) {
        VillageTemplate template = get(templateId);
        StringBuilder sb = new StringBuilder();
        for (TemplateStep step : template.getSteps()) {
            sb.append(escapeCsvCell(step.getBuildingType())).append(',');
            sb.append(step.getSlot() == null ? "" : step.getSlot()).append(',');
            sb.append(step.getTargetLevel()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Strip cascade for account deletion. Account-scoped data, no FKs into
     * other modules — a straight cascade delete is enough. Runs inside the
     * caller's transaction so the full delete-account flow rolls back as one.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountDeleting(AccountDeletingEvent event) {
        repository.deleteAllByAccountId(event.accountId());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void applySteps(VillageTemplate template, List<TemplateStepDto> incoming) {
        List<TemplateStep> existing = template.getSteps();
        var byId = existing.stream()
                .filter(s -> s.getId() != null)
                .collect(java.util.stream.Collectors.toMap(TemplateStep::getId, s -> s));

        List<TemplateStep> next = new ArrayList<>();
        int order = 0;
        for (TemplateStepDto dto : incoming) {
            TemplateStep step;
            if (dto.id() != null && byId.containsKey(dto.id())) {
                step = byId.get(dto.id());
                step.update(dto.buildingType(), dto.slot(), dto.targetLevel());
                step.setOrderIndex(order);
            } else {
                step = new TemplateStep(template, order, dto.buildingType(), dto.slot(), dto.targetLevel());
            }
            next.add(step);
            order++;
        }
        // orphanRemoval=true on the collection drops any existing rows not
        // present in `next`. Reassign in-place so Hibernate tracks the diff
        // correctly.
        existing.clear();
        existing.addAll(next);
    }

    private static List<TemplateStepDto> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("CSV is empty — nothing to import.");
        }
        List<TemplateStepDto> rows = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            // Skip comment lines (#) — convenience for users annotating their
            // paste; not round-tripped on export but tolerated on import.
            if (trimmed.startsWith("#")) continue;

            String[] parts = splitCsvLine(trimmed);
            if (parts.length < 3) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": expected 3 columns (buildingType,slot,targetLevel) but got "
                                + parts.length + ".");
            }
            String buildingType = parts[0].trim();
            if (buildingType.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": buildingType is required.");
            }
            Integer slot = null;
            String slotCell = parts[1].trim();
            if (!slotCell.isEmpty()) {
                try {
                    slot = Integer.valueOf(slotCell);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "Line " + (i + 1) + ": slot '" + slotCell + "' is not a number.");
                }
                if (slot < 1 || slot > 40) {
                    throw new IllegalArgumentException(
                            "Line " + (i + 1) + ": slot must be between 1 and 40 (got " + slot + ").");
                }
            }
            int targetLevel;
            String levelCell = parts[2].trim();
            try {
                targetLevel = Integer.parseInt(levelCell);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": targetLevel '" + levelCell + "' is not a number.");
            }
            if (targetLevel < 0 || targetLevel > 25) {
                throw new IllegalArgumentException(
                        "Line " + (i + 1) + ": targetLevel must be between 0 and 25 (got " + targetLevel + ").");
            }
            rows.add(new TemplateStepDto(null, buildingType, slot, targetLevel));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV contained no step rows.");
        }
        return rows;
    }

    /**
     * Split a CSV line on commas, honouring double-quoted cells so building
     * names with commas (none currently, but futureproof — "Warehouse, Large"
     * style mods exist) survive round-tripping. Not a full RFC 4180 parser;
     * that's overkill for our single-line, 3-column format.
     */
    private static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote inside a quoted cell.
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String escapeCsvCell(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
