package com.tripchat.exception;

/**
 * Thrown when the provided invite code doesn't match any active group.
 * Maps to 404 — same as GroupNotFoundException.
 *
 * Security: returning 404 (not 403) prevents confirming whether a group
 * exists at all. Attacker cannot distinguish "wrong code" from "no such group".
 */
public class InvalidInviteCodeException extends RuntimeException {
    public InvalidInviteCodeException() {
        super("Invalid invite code");
    }
}
