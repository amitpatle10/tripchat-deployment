package com.tripchat.security.strategy;

import com.tripchat.dto.request.LoginRequest;
import com.tripchat.dto.request.RegisterRequest;
import com.tripchat.dto.response.AuthResponse;

/**
 * AuthStrategy — Strategy Pattern contract for authentication mechanisms.
 *
 * Pattern: Strategy
 * Problem solved: multiple auth methods (email/password, Google OAuth2) have
 *   different inputs and verification logic but produce the same output (AuthResponse).
 *   Without this pattern, AuthService would grow an if-else chain for each provider.
 *
 * Open/Closed Principle:
 *   Adding Google login = create GoogleAuthStrategy implements AuthStrategy.
 *   Zero changes to AuthService, AuthController, or this interface.
 *
 * Current implementations: EmailPasswordAuthStrategy
 * Future implementations:  GoogleAuthStrategy, GitHubAuthStrategy
 */
public interface AuthStrategy {

    /**
     * Register a new user and return an AuthResponse containing a JWT.
     * Each strategy handles its own credential verification and user creation.
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Authenticate an existing user and return an AuthResponse containing a JWT.
     * Each strategy verifies credentials in its own way.
     */
    AuthResponse login(LoginRequest request);
}
