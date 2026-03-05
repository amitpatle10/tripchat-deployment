package com.tripchat.controller;

import com.tripchat.dto.request.CreateGroupRequest;
import com.tripchat.dto.request.JoinGroupRequest;
import com.tripchat.dto.response.GroupMemberResponse;
import com.tripchat.dto.response.GroupResponse;
import com.tripchat.service.GroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * GroupController — HTTP entry point for group operations.
 *
 * @AuthenticationPrincipal UserDetails:
 *   Spring Security injects the currently authenticated user.
 *   userDetails.getUsername() returns the email (set in CustomUserDetailsService).
 *   No manual JWT parsing needed — JwtAuthFilter already did it.
 *
 * All endpoints require authentication — enforced by SecurityConfig.
 * (Everything outside /api/v1/auth/** requires a valid JWT.)
 *
 * RESTful conventions followed:
 *   POST   /groups          → 201 Created
 *   GET    /groups          → 200 OK (list)
 *   GET    /groups/{id}     → 200 OK (single)
 *   POST   /groups/join     → 200 OK (join action, not creating a new resource)
 *   DELETE /groups/{id}/leave → 204 No Content
 *   POST   /groups/{id}/invite/regenerate → 200 OK
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.createGroup(userDetails.getUsername(), request));
    }

    @GetMapping
    public ResponseEntity<List<GroupResponse>> getMyGroups(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(groupService.getMyGroups(userDetails.getUsername()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GroupResponse> getGroup(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(groupService.getGroupById(userDetails.getUsername(), id));
    }

    @PostMapping("/join")
    public ResponseEntity<GroupResponse> joinGroup(
            @Valid @RequestBody JoinGroupRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(groupService.joinGroup(userDetails.getUsername(), request));
    }

    /**
     * GET /api/v1/groups/{id}/members
     *   Returns all members of the group. Any member may call this.
     *   ADMINs are listed first, then by join date ascending.
     *   404 for non-members — consistent with group access policy.
     */
    @GetMapping("/{id}/members")
    public ResponseEntity<List<GroupMemberResponse>> getGroupMembers(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(groupService.getGroupMembers(userDetails.getUsername(), id));
    }

    /**
     * DELETE /api/v1/groups/{id}
     *   Soft-deletes the group. Admin-only.
     *   403 if caller is a MEMBER; 404 if not a member or group doesn't exist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        groupService.deleteGroup(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/leave")
    public ResponseEntity<Void> leaveGroup(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        groupService.leaveGroup(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/invite/regenerate")
    public ResponseEntity<GroupResponse> regenerateInviteCode(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(groupService.regenerateInviteCode(userDetails.getUsername(), id));
    }

    // Mark all messages in a group as read — resets unread count to 0
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserDetails userDetails) {
        groupService.markAsRead(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
