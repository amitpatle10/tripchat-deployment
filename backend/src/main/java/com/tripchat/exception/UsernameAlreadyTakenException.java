package com.tripchat.exception;

/**
 * Thrown when a registration attempt uses a username already in the system.
 * Maps to HTTP 409 Conflict in GlobalExceptionHandler.
 */
public class UsernameAlreadyTakenException extends RuntimeException {

    public UsernameAlreadyTakenException(String username) {
        super("Username already taken: " + username);
    }
}
