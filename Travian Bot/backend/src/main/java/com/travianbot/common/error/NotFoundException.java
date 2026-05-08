package com.travianbot.common.error;

/**
 * Generic "resource not found" signal. Handled centrally by {@link GlobalExceptionHandler}.
 * Feature modules should extend this (or throw it directly with a descriptive message)
 * rather than depending on Spring HTTP types from their service layer.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
