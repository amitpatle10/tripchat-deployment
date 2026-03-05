package com.tripchat.exception;

/** Thrown when the group ADMIN tries to leave. Maps to 400. */
public class AdminCannotLeaveException extends RuntimeException {
    public AdminCannotLeaveException() {
        super("Group admin cannot leave — delete the group instead");
    }
}
