package com.tripchat.controller;

import com.tripchat.dto.response.MessageResponse;
import com.tripchat.service.MessageDeleteService;
import com.tripchat.service.MessageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MessageRestController — REST API for message history.
 *
 * Separate from MessageController (WebSocket) to keep concerns clean:
 *   MessageController    → real-time STOMP message sending
 *   MessageRestController → HTTP message history retrieval
 *
 * Endpoint: GET /api/v1/groups/{groupId}/messages
 *   RESTful resource nesting — messages are a sub-resource of groups.
 *   Only group members can access (validated in MessageQueryService).
 *
 * Cursor-based pagination:
 *   First page:  GET /api/v1/groups/{id}/messages
 *   Next page:   GET /api/v1/groups/{id}/messages?cursorTime=...&cursorId=...
 *
 *   cursorTime = ISO-8601 timestamp of the oldest message on current page
 *   cursorId   = UUID of the oldest message on current page
 *   Both required together — composite cursor handles same-millisecond messages.
 *
 * Response: 200 OK with List<MessageResponse>
 *   Empty list = no more messages (client stops paginating)
 *   No pagination metadata needed — empty list is the terminal signal.
 */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/messages")
@RequiredArgsConstructor
public class MessageRestController {

    private final MessageQueryService messageQueryService;
    private final MessageDeleteService messageDeleteService;

    /**
     * GET /api/v1/groups/{groupId}/messages
     *   First page — returns latest 50 messages, newest first.
     *
     * GET /api/v1/groups/{groupId}/messages?cursorTime=...&cursorId=...
     *   Subsequent pages — returns 50 messages older than the cursor.
     */
    @GetMapping
    public ResponseEntity<List<MessageResponse>> getMessages(
            @PathVariable UUID groupId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorTime,
            @RequestParam(required = false) UUID cursorId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String email = userDetails.getUsername();

        List<MessageResponse> messages = (cursorTime != null && cursorId != null)
            ? messageQueryService.getMessagesBefore(email, groupId, cursorTime, cursorId)
            : messageQueryService.getMessages(email, groupId);

        return ResponseEntity.ok(messages);
    }

    /**
     * DELETE /api/v1/groups/{groupId}/messages/{messageId}
     *   Soft-deletes a message. Only the sender can delete their own message.
     *   Broadcasts deletion to all online group members via STOMP.
     *
     * 204 No Content — success, no body needed.
     * 403 Forbidden  — authenticated user is not the message sender.
     * 404 Not Found  — group or message doesn't exist, or caller is not a member.
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable UUID groupId,
            @PathVariable UUID messageId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        messageDeleteService.deleteMessage(userDetails.getUsername(), groupId, messageId);
        return ResponseEntity.noContent().build();
    }
}
