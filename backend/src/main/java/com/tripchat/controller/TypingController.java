package com.tripchat.controller;

import com.tripchat.dto.request.TypingRequest;
import com.tripchat.service.TypingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * TypingController — STOMP handler for typing indicator events.
 *
 * Client sends:  STOMP SEND /app/groups/{groupId}/typing
 * Server broadcasts to: /topic/groups/{groupId}/typing
 *
 * No return value — broadcast is done inside TypingService via
 * SimpMessagingTemplate (same pattern as MessageController).
 */
@Controller
@RequiredArgsConstructor
public class TypingController {

    private final TypingService typingService;

    @MessageMapping("/groups/{groupId}/typing")
    public void typing(
            @DestinationVariable UUID groupId,
            @Payload @Valid TypingRequest request,
            Principal principal
    ) {
        typingService.handleTyping(principal.getName(), groupId, request.getTyping());
    }
}
