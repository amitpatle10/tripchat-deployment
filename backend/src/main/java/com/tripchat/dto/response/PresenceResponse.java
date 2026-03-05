package com.tripchat.dto.response;

import com.tripchat.model.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/**
 * PresenceResponse — one entry in the GET /groups/{groupId}/presence response.
 *
 * Returns basic identity info for each online member.
 * Client uses this to render the green dot next to member names.
 *
 * Static factory pattern — same as MessageResponse.from(Message).
 * Keeps the controller/service free of mapping logic.
 */
@Getter
@AllArgsConstructor
public class PresenceResponse {

    private UUID userId;
    private String username;
    private String displayName;

    public static PresenceResponse from(User user) {
        return new PresenceResponse(
            user.getId(),
            user.getUsername(),
            user.getDisplayName()
        );
    }
}
