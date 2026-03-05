package com.tripchat.config;

import com.tripchat.security.jwt.JwtChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig — configures STOMP over WebSocket.
 *
 * Pattern: Configuration Object (creational) — encapsulates all WebSocket
 * settings in one place, keeping configuration separate from logic.
 *
 * Broker design:
 *   SimpleBroker (in-memory) chosen over external broker (RabbitMQ).
 *   At 1 server and 1000 DAUs, SimpleBroker is zero-config and sufficient.
 *   When we scale to multiple servers, Redis Pub/Sub will be added as the
 *   cross-server broadcast layer — SimpleBroker stays for local session routing.
 *
 * Endpoint: /ws
 *   All WebSocket connections hit this endpoint.
 *   SockJS enabled — falls back to HTTP long-polling for clients in
 *   environments that block WebSocket (corporate firewalls, older proxies).
 *
 * App prefix: /app
 *   Messages sent by clients to /app/... are routed to @MessageMapping methods.
 *   Example: client SEND /app/groups/abc/messages → @MessageMapping("/groups/{id}/messages")
 *
 * Topic prefix: /topic
 *   Clients subscribe to /topic/... to receive broadcasts.
 *   Example: client SUBSCRIBE /topic/groups/abc → receives all messages for group abc.
 *   Server calls convertAndSend("/topic/groups/abc", msg) to push to all subscribers.
 *
 * Auth: JwtChannelInterceptor
 *   Intercepts STOMP CONNECT frame, validates JWT from Authorization header.
 *   Browsers can't set custom headers on WebSocket handshake — STOMP CONNECT
 *   frame is the first opportunity to authenticate after the connection is open.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    // Default "*" keeps local dev working without setting the env var.
    // In prod the application-prod.yml sets this to the CloudFront domain via ${ALLOWED_ORIGINS}.
    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker handles:
        //   /topic/... — group broadcasts (one-to-many)
        //   /queue/...  — user-specific messages (one-to-one, used by @SendToUser)
        registry.enableSimpleBroker("/topic", "/queue");

        // Messages from clients to /app/... are routed to @MessageMapping handlers
        registry.setApplicationDestinationPrefixes("/app");

        // Prefix for @SendToUser destinations — Spring rewrites
        // /user/queue/confirmation → /queue/confirmation-{sessionId}
        // so only the intended user receives it
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // In dev: defaults to "*" (see field above).
                // In prod: set to the CloudFront domain via the ALLOWED_ORIGINS env var.
                .setAllowedOriginPatterns(allowedOrigins)
                // SockJS fallback for environments that block WebSocket
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Chain of Responsibility pattern — interceptor validates JWT before
        // any @MessageMapping handler processes the message
        registration.interceptors(jwtChannelInterceptor);
    }
}
