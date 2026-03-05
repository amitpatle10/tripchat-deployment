package com.tripchat.exception;

/** Thrown when a group has reached the maximum member limit (1000). Maps to 400. */
public class GroupFullException extends RuntimeException {
    public GroupFullException() {
        super("This group has reached the maximum member limit of 1000");
    }
}
