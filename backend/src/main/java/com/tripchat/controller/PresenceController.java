package com.tripchat.controller;

import com.tripchat.repository.UserRepository;
import com.tripchat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * PresenceController — STOMP handler for client heartbeats.
 *
 * Client sends: STOMP SEND /app/presence/heartbeat  (every 20s)
 * Server:       refreshes presence:{userId} TTL in Redis
 *
 * Why 20s heartbeat vs 30s TTL?
 *   Gives a 10s buffer. If one heartbeat is delayed or lost, the next one
 *   arrives before the TTL expires. Two consecutive missed heartbeats (40s)
 *   would cause expiry — acceptable for a presence indicator.
 *
 * No payload required — the principal from the STOMP session is enough.
 * No response sent back — fire-and-forget from the client's perspective.
 */
@Controller
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @MessageMapping("/presence/heartbeat")
    public void heartbeat(Principal principal) {
        userRepository.findByEmailIgnoreCase(principal.getName())
                .ifPresent(user -> presenceService.refreshPresence(user.getId()));
    }
}
