package com.tripchat.exception;

import java.util.UUID;

/** Thrown when a group ID does not exist or the user is not a member. Maps to 404. */
public class GroupNotFoundException extends RuntimeException {
    public GroupNotFoundException(UUID groupId) {
        super("Group not found: " + groupId);
    }
}
