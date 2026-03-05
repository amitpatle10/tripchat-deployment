package com.tripchat.exception;

/**
 * Thrown when a registration attempt uses an email already in the system.
 * Maps to HTTP 409 Conflict in GlobalExceptionHandler.
 *
 * RuntimeException (unchecked) — callers don't need to declare or catch it.
 * The GlobalExceptionHandler catches it centrally.
 */
public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email already in use: " + email);
    }
}
