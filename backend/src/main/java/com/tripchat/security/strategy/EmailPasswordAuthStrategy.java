package com.tripchat.security.strategy;

import com.tripchat.dto.request.LoginRequest;
import com.tripchat.dto.request.RegisterRequest;
import com.tripchat.dto.response.AuthResponse;
import com.tripchat.dto.response.UserResponse;
import com.tripchat.exception.EmailAlreadyExistsException;
import com.tripchat.exception.InvalidCredentialsException;
import com.tripchat.exception.UsernameAlreadyTakenException;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.repository.UserRepository;
import com.tripchat.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * EmailPasswordAuthStrategy — Strategy Pattern implementation for LOCAL auth.
 *
 * Pattern: Strategy (implements AuthStrategy)
 * Handles registration for users authenticating with email + password.
 * Isolated from GoogleAuthStrategy — adding Google login won't touch this class.
 *
 * Login — delegates to Spring's AuthenticationManager:
 *   AuthenticationManager.authenticate() internally:
 *     1. Calls CustomUserDetailsService.loadUserByUsername(email)
 *     2. Compares raw password against BCrypt hash via PasswordEncoder.matches()
 *     3. Checks accountLocked (!isActive)
 *   Throws AuthenticationException on any failure — we catch ALL cases and
 *   throw InvalidCredentialsException with a generic message (no user enumeration).
 *
 * Duplicate detection — pessimistic check before insert:
 *   We check email and username existence BEFORE attempting the INSERT.
 *   Tradeoff vs optimistic (try-insert, catch violation):
 *     Pessimistic: 2 SELECT queries + INSERT — slightly more DB calls,
 *                  but gives us specific error messages per field.
 *     Optimistic:  1 INSERT — fewer queries, but DataIntegrityViolationException
 *                  requires parsing the constraint name to know which field failed.
 *   Decision: pessimistic — clarity over micro-optimization at 1000 DAUs.
 *   Race condition risk is negligible (simultaneous same-email registrations are rare).
 *
 * Password hashing:
 *   BCrypt via PasswordEncoder — injected, not instantiated here.
 *   Allows swapping to Argon2 later without changing this class (DI + OCP).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailPasswordAuthStrategy implements AuthStrategy {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Override
    public AuthResponse register(RegisterRequest request) {
        // Check duplicates with specific error messages per field
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        if (userRepository.existsByUsernameIgnoreCase(request.getUsername())) {
            throw new UsernameAlreadyTakenException(request.getUsername());
        }

        // Build user entity — Builder pattern (Lombok @Builder on User)
        User user = User.builder()
                .email(request.getEmail().toLowerCase())         // normalize to lowercase
                .username(request.getUsername().toLowerCase())   // normalize to lowercase
                .displayName(request.getDisplayName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider(AuthProvider.LOCAL)
                .build();

        User savedUser = userRepository.save(user);
        log.info("New user registered: {} ({})", savedUser.getUsername(), savedUser.getId());

        String token = jwtService.generateToken(savedUser);

        return AuthResponse.builder()
                .token(token)
                .expiresIn(jwtService.getExpirationMs())
                .user(UserResponse.from(savedUser))
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        // Delegate credential verification entirely to Spring Security.
        // Catches ALL AuthenticationException subtypes (BadCredentialsException,
        // UsernameNotFoundException, LockedException) and maps to one generic
        // exception — prevents user enumeration.
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getPassword()
                    )
            );
        } catch (AuthenticationException e) {
            throw new InvalidCredentialsException();
        }

        // Authentication passed — load full User entity to build JWT
        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        log.info("User logged in: {} ({})", user.getUsername(), user.getId());

        String token = jwtService.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .expiresIn(jwtService.getExpirationMs())
                .user(UserResponse.from(user))
                .build();
    }
}
