package com.travianbot.attackplan.api;

import com.travianbot.attackplan.api.dto.AttackPlanMatrixResponse;
import com.travianbot.attackplan.api.dto.AttackPlanRequest;
import com.travianbot.attackplan.api.dto.AttackPlanResponse;
import com.travianbot.attackplan.service.AttackPlanService;
import com.travianbot.attackplan.service.AttackPlanService.TargetInput;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for the war-op planner.
 * <ul>
 *   <li>{@code GET    /api/accounts/{accountId}/attack-plans}        — every plan for the account</li>
 *   <li>{@code POST   /api/accounts/{accountId}/attack-plans}        — create</li>
 *   <li>{@code GET    /api/accounts/{accountId}/attack-plans/{id}}   — load one</li>
 *   <li>{@code PUT    /api/accounts/{accountId}/attack-plans/{id}}   — replace targets + sources + meta</li>
 *   <li>{@code DELETE /api/accounts/{accountId}/attack-plans/{id}}   — drop the plan</li>
 *   <li>{@code GET    /api/accounts/{accountId}/attack-plans/{id}/matrix} — per-(source × target) travel + launch</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounts/{accountId}/attack-plans")
public class AttackPlanController {

    private final AttackPlanService service;

    public AttackPlanController(AttackPlanService service) {
        this.service = service;
    }

    @GetMapping
    public List<AttackPlanResponse> list(@PathVariable Long accountId) {
        return service.listFor(accountId).stream().map(AttackPlanResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<AttackPlanResponse> create(
            @PathVariable Long accountId,
            @RequestBody AttackPlanRequest body) {
        var plan = service.create(
                accountId,
                body.name(),
                body.speedFieldsPerHour(),
                body.landingAt(),
                toTargetInputs(body),
                body.sourceVillageIds()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(AttackPlanResponse.from(plan));
    }

    @GetMapping("/{id}")
    public AttackPlanResponse get(@PathVariable Long accountId, @PathVariable Long id) {
        return AttackPlanResponse.from(service.get(accountId, id));
    }

    @PutMapping("/{id}")
    public AttackPlanResponse update(
            @PathVariable Long accountId,
            @PathVariable Long id,
            @RequestBody AttackPlanRequest body) {
        var plan = service.update(
                accountId, id,
                body.name(),
                body.speedFieldsPerHour(),
                body.landingAt(),
                toTargetInputs(body),
                body.sourceVillageIds()
        );
        return AttackPlanResponse.from(plan);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long accountId, @PathVariable Long id) {
        service.delete(accountId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/matrix")
    public AttackPlanMatrixResponse matrix(@PathVariable Long accountId, @PathVariable Long id) {
        return AttackPlanMatrixResponse.from(service.computeMatrix(accountId, id));
    }

    private static List<TargetInput> toTargetInputs(AttackPlanRequest body) {
        if (body.targets() == null) return List.of();
        return body.targets().stream()
                .filter(t -> t != null)
                .map(t -> new TargetInput(t.x(), t.y(), t.label()))
                .toList();
    }
}
