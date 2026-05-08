package com.travianbot.task.api.dto;

import com.travianbot.task.domain.BotTask;
import com.travianbot.task.domain.TaskStatus;

import java.time.Instant;

public record TaskResponse(
        Long id,
        Long accountId,
        String type,
        String description,
        TaskStatus status,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        String resultMessage
) {
    public static TaskResponse from(BotTask t) {
        return new TaskResponse(
                t.getId(),
                t.getAccount().getId(),
                t.getType(),
                t.getDescription(),
                t.getStatus(),
                t.getScheduledAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getStartedAt(),
                t.getCompletedAt(),
                t.getResultMessage()
        );
    }
}
