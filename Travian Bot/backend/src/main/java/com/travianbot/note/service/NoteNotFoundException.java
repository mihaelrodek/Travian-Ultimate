package com.travianbot.note.service;

import com.travianbot.common.error.NotFoundException;

public class NoteNotFoundException extends NotFoundException {
    public NoteNotFoundException(Long id) {
        super("Note " + id + " not found");
    }
}
