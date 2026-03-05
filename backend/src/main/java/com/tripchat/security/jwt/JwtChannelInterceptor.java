package com.tripchat.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/**
 * JwtChannelInterceptor — authenticates WebSocket connections via JWT.
 *
 * Pattern: Chain of Responsibility — this interceptor sits in the inbound
 * channel pipeline. Every incoming STOMP frame passes through it before
 * reaching any @MessageMapping handler.
 *
 * Why here, not at the HTTP handshake:
 *   Browsers cannot set custom headers (like Authorization) on the WebSocket
 *   HTTP upgrade request — it's a browser security restriction.
 *   The STOMP CONNECT frame is sent immediately after the WebSocket connection
 *   opens and is the first opportunity to carry the JWT.
 *
 * What this interceptor does:
 *   1. Intercepts STOMP CONNECT frames only (other frame types pass through)
 *   2. Reads the Authorization header from the STOMP frame
 *   3. Validates the JWT using JwtService
 *   4. Loads the UserDetails and sets the authenticated principal on the session
 *   5. Rejects invalid tokens by throwing MessageDeliveryException
 *
 * After this interceptor runs successfully:
 *   The WebSocket session has an authenticated principal.
 *   @MessageMapping methods can call Principal.getName() to get the user's email.
 *   Spring Security considers the session authenticated for all subsequent frames.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // Only authenticate on CONNECT — other frames inherit the session principal
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("WebSocket CONNECT rejected — missing or malformed Authorization header");
                throw new org.springframework.messaging.MessageDeliveryException(
                    message, "Missing Authorization header"
                );
            }

            String token = authHeader.substring(7);

            if (!jwtService.isTokenValid(token)) {
                log.warn("WebSocket CONNECT rejected — invalid JWT");
                throw new org.springframework.messaging.MessageDeliveryException(
                    message, "Invalid or expired token"
                );
            }

            String email = jwtService.extractEmail(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            // Set authenticated principal on the STOMP session
            // All subsequent frames in this session inherit this principal
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities()
                );
            accessor.setUser(auth);

            log.debug("WebSocket CONNECT authenticated: {}", email);
        }

        return message;
    }
}
