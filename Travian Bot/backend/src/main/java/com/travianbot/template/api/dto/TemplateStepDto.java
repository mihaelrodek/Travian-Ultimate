package com.travianbot.template.api.dto;

import com.travianbot.template.domain.TemplateStep;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One line of a {@link com.travianbot.template.domain.VillageTemplate}.
 * <p>
 * {@code id} is round-tripped so the client can preserve row identity when
 * editing a saved template — the replace-all reconciliation in
 * {@code VillageTemplateService} uses it to keep stable DB rows instead of
 * creating new ones on every save.
 */
public record TemplateStepDto(
        Long id,
        @NotBlank @Size(max = 64) String buildingType,
        @Min(1) @Max(40) Integer slot,
        @Min(0) @Max(25) int targetLevel
) {
    public static TemplateStepDto from(TemplateStep step) {
        return new TemplateStepDto(
                step.getId(),
                step.getBuildingType(),
                step.getSlot(),
                step.getTargetLevel()
        );
    }
}
