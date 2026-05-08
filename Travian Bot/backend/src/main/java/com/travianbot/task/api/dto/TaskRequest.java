package com.travianbot.task.api.dto;

import com.travianbot.task.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create/update body for a task. {@code accountId} required on create; ignored on update.
 * {@code status} is optional — if provided on update, status transitions.
 */
public record TaskRequest(
        Long accountId,
        @NotBlank @Size(max = 64) String type,
        @Size(max = 500) String description,
        Instant scheduledAt,
        TaskStatus status,
        String resultMessage
) {
}
