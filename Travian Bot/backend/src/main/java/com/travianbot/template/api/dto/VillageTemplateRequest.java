package com.travianbot.template.api.dto;

import com.travianbot.template.domain.TemplateCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create/update payload for a {@link com.travianbot.template.domain.VillageTemplate}.
 * <p>
 * {@code accountId} is required on create and ignored on update (we don't let
 * users move a template between accounts — the steps make sense in the context
 * of a server's game data and moving would be confusing).
 */
public record VillageTemplateRequest(
        Long accountId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        @NotNull TemplateCategory category,
        @NotNull @Valid List<@Valid TemplateStepDto> steps
) {
}
