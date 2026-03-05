package com.tripchat.messaging;

import com.tripchat.repository.UserRepository;
import com.tripchat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * SessionEventListener — tracks WebSocket connect/disconnect for presence.
 *
 * Pattern: Observer — Spring publishes WebSocket lifecycle events;
 * this listener reacts to mark users online/offline in Redis.
 *
 * Why not do this in JwtChannelInterceptor?
 *   Single Responsibility. The interceptor's job is auth — validate JWT and
 *   set the principal. Presence tracking is a separate concern. Mixing them
 *   would make the interceptor harder to test and harder to change.
 *   Spring's event system lets us keep them completely independent.
 *
 * SessionConnectedEvent:
 *   Fires after STOMP CONNECT is fully processed and the session is established.
 *   At this point the principal is set (JwtChannelInterceptor has run).
 *   Safe to read accessor.getUser() here.
 *
 * SessionDisconnectEvent:
 *   Fires when the WebSocket session closes — cleanly (tab closed, logout) or
 *   abnormally (server-detected). The principal is still accessible on the event.
 *   Ungraceful disconnects (network drop) are handled by Redis TTL expiry.
 *
 * DB call on connect/disconnect:
 *   We look up the user by email to get their UUID for the Redis key.
 *   This is one indexed DB query on a relatively infrequent event.
 *   Acceptable — users don't connect/disconnect thousands of times per second.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionEventListener {

    private final PresenceService presenceService;
    private final UserRepository userRepository;

    @EventListener
    public void handleConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        if (accessor.getUser() == null) return;  // unauthenticated session — skip

        String email = accessor.getUser().getName();
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            presenceService.markOnline(user.getId());
            log.debug("Presence: {} ({}) came online", user.getUsername(), user.getId());
        });
    }

    @EventListener
    public void handleDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        if (accessor.getUser() == null) return;

        String email = accessor.getUser().getName();
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            presenceService.markOffline(user.getId());
            log.debug("Presence: {} ({}) went offline", user.getUsername(), user.getId());
        });
    }
}
