package com.tripchat.exception;

/** Thrown when a MEMBER tries to perform an ADMIN-only action. Maps to 403. */
public class UnauthorizedGroupActionException extends RuntimeException {
    public UnauthorizedGroupActionException(String action) {
        super("Only group admin can: " + action);
    }
}
