package com.travianbot.template.api;

import com.travianbot.template.api.dto.VillageTemplateRequest;
import com.travianbot.template.api.dto.VillageTemplateResponse;
import com.travianbot.template.domain.TemplateCategory;
import com.travianbot.template.service.VillageTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for village templates: CRUD + CSV import/export + the
 * "apply to village" action that seeds a village's build queue.
 * <p>
 * Two endpoints kept deliberately separate from the main CRUD:
 * <ul>
 *   <li>{@code POST /import-csv} — creates a new template from a pasted CSV
 *       body. Factored out of plain create because the UX is a different
 *       form (textarea + category select) and merging the two would make
 *       the create DTO carry optional raw-csv baggage.</li>
 *   <li>{@code GET /{id}/export-csv} — returns a {@code text/csv} body so
 *       the browser's default "open with" or "save as" works without any
 *       custom Content-Disposition dance.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/templates")
public class VillageTemplateController {

    private final VillageTemplateService service;

    public VillageTemplateController(VillageTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<VillageTemplateResponse> list(@RequestParam(required = false) Long accountId) {
        return service.list(accountId).stream().map(VillageTemplateResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VillageTemplateResponse get(@PathVariable Long id) {
        return VillageTemplateResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<VillageTemplateResponse> create(@Valid @RequestBody VillageTemplateRequest request) {
        VillageTemplateResponse body = VillageTemplateResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public VillageTemplateResponse update(@PathVariable Long id, @Valid @RequestBody VillageTemplateRequest request) {
        return VillageTemplateResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Apply a template to a village — appends the template's steps onto the
     * village's build queue. Returns 200 + the number of rows added so the
     * UI can pop a "Added N steps" toast without a follow-up fetch (the
     * caller will re-fetch the village for the full queue anyway).
     */
    @PostMapping("/{id}/apply")
    public ApplyResponse apply(@PathVariable Long id, @RequestParam Long villageId) {
        int added = service.applyToVillage(id, villageId);
        return new ApplyResponse(added);
    }

    @PostMapping("/import-csv")
    public ResponseEntity<VillageTemplateResponse> importCsv(@Valid @RequestBody CsvImportRequest req) {
        VillageTemplateResponse body = VillageTemplateResponse.from(
                service.importCsv(req.accountId(), req.name(), req.description(), req.category(), req.csv())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping(value = "/{id}/export-csv", produces = "text/csv; charset=UTF-8")
    public ResponseEntity<String> exportCsv(@PathVariable Long id) {
        String csv = service.exportCsv(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    public record ApplyResponse(int added) {}

    public record CsvImportRequest(
            @NotNull Long accountId,
            @NotBlank String name,
            String description,
            @NotNull TemplateCategory category,
            @NotBlank String csv
    ) {}
}
