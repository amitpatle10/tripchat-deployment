package com.tripchat.exception;

import java.util.UUID;

/** Thrown when a message ID does not exist within the given group. Maps to 404. */
public class MessageNotFoundException extends RuntimeException {
    public MessageNotFoundException(UUID messageId) {
        super("Message not found: " + messageId);
    }
}
