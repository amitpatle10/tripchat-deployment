package com.tripchat.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * AuthResponse DTO — returned on successful register and login.
 *
 * Returns token immediately on register (no separate login step for Phase 1).
 * Tradeoff: skips email verification — acceptable for Phase 1 scope.
 *
 * tokenType: always "Bearer" — tells the client how to send it.
 *   Authorization: Bearer <token>
 *
 * expiresIn: milliseconds until token expiry — client uses this to
 *   schedule a token refresh before expiry rather than waiting for a 401.
 */
@Getter
@Builder
public class AuthResponse {

    private String token;

    @Builder.Default
    private String tokenType = "Bearer";

    private long expiresIn;

    private UserResponse user;
}
