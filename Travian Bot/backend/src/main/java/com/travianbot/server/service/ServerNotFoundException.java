package com.travianbot.server.service;

import com.travianbot.common.error.NotFoundException;

/**
 * Thrown when a {@code Server} lookup by id fails. Extends the project-wide
 * {@link NotFoundException} so the existing {@code @RestControllerAdvice}
 * translates it into a 404 with no extra handler needed.
 */
public class ServerNotFoundException extends NotFoundException {
    public ServerNotFoundException(Long id) {
        super("Server not found: " + id);
    }
}
