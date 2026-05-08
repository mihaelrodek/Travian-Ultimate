package com.travianbot.note.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code accountId} is required on create; ignored on update.
 */
public record NoteRequest(
        Long accountId,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 65_535) String body
) {
}
