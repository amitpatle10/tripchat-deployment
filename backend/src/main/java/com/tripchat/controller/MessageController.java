package com.tripchat.controller;

import com.tripchat.dto.request.SendMessageRequest;
import com.tripchat.dto.response.MessageResponse;
import com.tripchat.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * MessageController — WebSocket STOMP message handler.
 *
 * @Controller (not @RestController):
 *   WebSocket handlers are not HTTP endpoints.
 *   @MessageMapping routes STOMP messages, not HTTP requests.
 *
 * @MessageMapping("/groups/{groupId}/messages"):
 *   Client sends: SEND /app/groups/{groupId}/messages
 *   (/app prefix configured in WebSocketConfig)
 *   Spring routes it here automatically.
 *
 * Principal:
 *   Injected by Spring from the authenticated session.
 *   Set by JwtChannelInterceptor on STOMP CONNECT.
 *   Principal.getName() returns the user's email (from UserDetails).
 *
 * @SendToUser("/queue/errors"):
 *   If processing fails, the error response is sent only to the sender.
 *   Other group members are not notified of sender-specific errors.
 *   Not used in the happy path — broadcast is done inside MessageService
 *   via SimpMessagingTemplate to reach all subscribers.
 *
 * Return value:
 *   Returned to the sender as confirmation of receipt (only they get this).
 *   All group members receive the broadcast via SimpMessagingTemplate
 *   inside MessageService — that's the actual delivery mechanism.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class MessageController {

    private final MessageService messageService;

    @MessageMapping("/groups/{groupId}/messages")
    @SendToUser("/queue/confirmation")
    public MessageResponse sendMessage(
            @DestinationVariable UUID groupId,
            @Payload @Valid SendMessageRequest request,
            Principal principal
    ) {
        log.debug("STOMP message received: group={}, clientId={}, user={}",
                groupId, request.getClientId(), principal.getName());

        return messageService.sendMessage(principal.getName(), groupId, request);
    }
}
