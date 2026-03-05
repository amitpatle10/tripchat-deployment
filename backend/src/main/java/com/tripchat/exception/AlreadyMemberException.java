package com.tripchat.exception;

/** Thrown when a user tries to join a group they're already a member of. Maps to 409. */
public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException() {
        super("You are already a member of this group");
    }
}
