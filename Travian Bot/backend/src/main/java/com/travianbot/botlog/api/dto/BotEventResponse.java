package com.travianbot.botlog.api.dto;

import com.travianbot.botlog.domain.BotEvent;
import com.travianbot.botlog.domain.BotEventOutcome;

import java.time.Instant;

public record BotEventResponse(
        Long id,
        Long accountId,
        Long villageId,
        String action,
        BotEventOutcome outcome,
        String message,
        Instant createdAt
) {
    public static BotEventResponse from(BotEvent e) {
        return new BotEventResponse(
                e.getId(),
                e.getAccount().getId(),
                e.getVillageId(),
                e.getAction(),
                e.getOutcome(),
                e.getMessage(),
                e.getCreatedAt()
        );
    }
}
