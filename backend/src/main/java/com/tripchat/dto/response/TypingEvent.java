package com.tripchat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/**
 * TypingEvent — broadcast payload sent to /topic/groups/{groupId}/typing.
 *
 * Received by all group members subscribed to the typing topic.
 * Client uses (userId, typing) to update its local "who is typing" list.
 *
 * userId included so clients can suppress their own event
 * (no need to show "You are typing" to yourself).
 */
@Getter
@AllArgsConstructor
public class TypingEvent {

    private UUID userId;
    private String username;
    private String displayName;
    private boolean typing;
}
