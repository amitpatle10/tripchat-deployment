package com.tripchat.controller;

import com.tripchat.dto.response.PresenceResponse;
import com.tripchat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * PresenceRestController — REST endpoint for querying group online presence.
 *
 * GET /api/v1/groups/{groupId}/presence
 *   Returns the list of currently online members in a group.
 *   Called once when a user opens a group — no polling, no WebSocket.
 *
 * Access control:
 *   Same as message history — non-members get 404, not 403.
 *   Don't reveal group existence to unauthorized users.
 *
 * Response: 200 OK with List<PresenceResponse>
 *   Empty list = no members currently online (or group is empty).
 */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/presence")
@RequiredArgsConstructor
public class PresenceRestController {

    private final PresenceService presenceService;

    @GetMapping
    public ResponseEntity<List<PresenceResponse>> getOnlineMembers(
            @PathVariable UUID groupId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        List<PresenceResponse> online = presenceService.getOnlineMembers(
                userDetails.getUsername(), groupId
        );
        return ResponseEntity.ok(online);
    }
}
