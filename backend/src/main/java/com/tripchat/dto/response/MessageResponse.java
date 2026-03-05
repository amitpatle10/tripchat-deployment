package com.tripchat.dto.response;

import com.tripchat.model.Message;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * MessageResponse — outbound DTO for a chat message.
 *
 * Pattern: Static Factory Method (from()) — centralises the mapping from
 * entity to DTO. Callers never access entity internals directly.
 * Same pattern used by UserResponse and GroupResponse in this codebase.
 *
 * senderUsername / senderDisplayName:
 *   Null-safe — sender can be null if the user deleted their account (SET NULL).
 *   UI shows "Deleted User" when both are null.
 *
 * deleted:
 *   True when deletedAt is non-null. Content is replaced by the client
 *   with "This message was deleted" — we still send the original content
 *   here but the client UI overrides it for deleted messages.
 *   Alternative: send null content for deleted messages — but then clients
 *   can't distinguish "null content" from "server error". Explicit flag is cleaner.
 */
@Getter
@Builder
public class MessageResponse {

    private UUID id;
    private UUID groupId;
    private UUID senderId;
    private String senderUsername;
    private String senderDisplayName;
    private String content;
    private UUID clientId;
    private Instant createdAt;
    private boolean deleted;

    /**
     * Static Factory Method — maps Message entity to MessageResponse DTO.
     */
    public static MessageResponse from(Message message) {
        boolean isDeleted = message.getDeletedAt() != null;
        return MessageResponse.builder()
                .id(message.getId())
                .groupId(message.getGroup().getId())
                .senderId(message.getSender() != null ? message.getSender().getId() : null)
                .senderUsername(message.getSender() != null ? message.getSender().getUsername() : null)
                .senderDisplayName(message.getSender() != null ? message.getSender().getDisplayName() : null)
                .content(message.getContent())
                .clientId(message.getClientId())
                .createdAt(message.getCreatedAt())
                .deleted(isDeleted)
                .build();
    }
}
