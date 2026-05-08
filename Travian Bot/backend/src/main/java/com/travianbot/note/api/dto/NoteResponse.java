package com.travianbot.note.api.dto;

import com.travianbot.note.domain.Note;

import java.time.Instant;

public record NoteResponse(
        Long id,
        Long accountId,
        String title,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    public static NoteResponse from(Note note) {
        return new NoteResponse(
                note.getId(),
                note.getAccount().getId(),
                note.getTitle(),
                note.getBody(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }
}
