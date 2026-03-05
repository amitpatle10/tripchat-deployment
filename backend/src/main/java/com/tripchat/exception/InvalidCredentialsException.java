package com.tripchat.exception;

/**
 * Thrown when login fails for any reason — wrong password, unknown email,
 * or inactive account.
 *
 * Security principle — user enumeration prevention:
 *   Always the same generic message regardless of WHY auth failed.
 *   "Invalid email or password" tells the attacker nothing about which field is wrong.
 *   Specific messages like "Email not found" confirm which emails exist in the system.
 *
 * Maps to HTTP 401 Unauthorized in GlobalExceptionHandler.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
