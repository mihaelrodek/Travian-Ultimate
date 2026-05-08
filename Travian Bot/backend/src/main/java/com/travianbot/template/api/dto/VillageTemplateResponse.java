package com.travianbot.template.api.dto;

import com.travianbot.template.domain.TemplateCategory;
import com.travianbot.template.domain.VillageTemplate;

import java.time.Instant;
import java.util.List;

public record VillageTemplateResponse(
        Long id,
        Long accountId,
        String name,
        String description,
        TemplateCategory category,
        Instant createdAt,
        Instant updatedAt,
        List<TemplateStepDto> steps
) {
    public static VillageTemplateResponse from(VillageTemplate template) {
        return new VillageTemplateResponse(
                template.getId(),
                template.getAccount().getId(),
                template.getName(),
                template.getDescription(),
                template.getCategory(),
                template.getCreatedAt(),
                template.getUpdatedAt(),
                template.getSteps().stream().map(TemplateStepDto::from).toList()
        );
    }
}
