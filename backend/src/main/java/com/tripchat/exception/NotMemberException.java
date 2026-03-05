package com.tripchat.exception;

/**
 * Thrown when a non-member tries to perform a member-only action.
 * Maps to 404 (not 403) — don't confirm the group exists to non-members.
 */
public class NotMemberException extends RuntimeException {
    public NotMemberException() {
        super("Group not found");
    }
}
