package com.travianbot.farmlist.api.dto;

import com.travianbot.farmlist.domain.Farmlist;
import com.travianbot.farmlist.domain.FarmlistType;

import java.time.Instant;
import java.util.List;

public record FarmlistResponse(
        Long id,
        Long accountId,
        String name,
        String description,
        boolean enabled,
        FarmlistType type,
        Integer sendIntervalMinutes,
        String groupTag,
        Instant lastSentAt,
        Long travianListId,
        Instant createdAt,
        Instant updatedAt,
        List<FarmlistTargetDto> targets
) {
    public static FarmlistResponse from(Farmlist f) {
        return new FarmlistResponse(
                f.getId(),
                f.getAccount().getId(),
                f.getName(),
                f.getDescription(),
                f.isEnabled(),
                f.getType(),
                f.getSendIntervalMinutes(),
                f.getGroupTag(),
                f.getLastSentAt(),
                f.getTravianListId(),
                f.getCreatedAt(),
                f.getUpdatedAt(),
                f.getTargets().stream().map(FarmlistTargetDto::from).toList()
        );
    }
}
