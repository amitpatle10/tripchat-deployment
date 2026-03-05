package com.tripchat.service;

import com.tripchat.dto.request.LoginRequest;
import com.tripchat.dto.response.AuthResponse;
import com.tripchat.exception.InvalidCredentialsException;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.repository.UserRepository;
import com.tripchat.security.jwt.JwtService;
import com.tripchat.security.strategy.EmailPasswordAuthStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailPasswordAuthStrategy — login()")
class AuthServiceLoginTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private EmailPasswordAuthStrategy authStrategy;

    // PasswordEncoder needed by @InjectMocks even though login doesn't use it
    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private LoginRequest validRequest;
    private User activeUser;

    @BeforeEach
    void setUp() {
        validRequest = new LoginRequest("amit@test.com", "StrongPass1!");

        activeUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .passwordHash("hashed_password")
                .authProvider(AuthProvider.LOCAL)
                .isActive(true)
                .build();
    }

    // ── Happy Path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return token when credentials are valid")
    void shouldLoginSuccessfullyWithValidCredentials() {
        // Arrange — AuthenticationManager succeeds (no exception = success)
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken("amit@test.com", null)
        );
        when(userRepository.findByEmailIgnoreCase("amit@test.com"))
                .thenReturn(Optional.of(activeUser));
        when(jwtService.generateToken(activeUser)).thenReturn("mock.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(86400000L);

        // Act
        AuthResponse response = authStrategy.login(validRequest);

        // Assert
        assertThat(response.getToken()).isEqualTo("mock.jwt.token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getUser().getEmail()).isEqualTo("amit@test.com");
        assertThat(response.getUser().getUsername()).isEqualTo("amit_patle");

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(jwtService).generateToken(activeUser);
    }

    // ── Failure Cases ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw InvalidCredentialsException when password is wrong")
    void shouldThrowWhenPasswordIsWrong() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authStrategy.login(validRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        // User should never be loaded from DB — fail fast at auth stage
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("should throw InvalidCredentialsException when email is not registered")
    void shouldThrowWhenEmailNotFound() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("User not found"));

        assertThatThrownBy(() -> authStrategy.login(validRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");   // same message — no enumeration

        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("should throw InvalidCredentialsException when account is inactive")
    void shouldThrowWhenAccountIsInactive() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new LockedException("Account locked"));

        assertThatThrownBy(() -> authStrategy.login(validRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");   // same message — no enumeration

        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("should return same error message for all failure types — user enumeration prevention")
    void shouldReturnSameMessageForAllFailures() {
        // All three failure types produce the exact same exception + message
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("any reason"));

        String message1 = null;
        try { authStrategy.login(validRequest); }
        catch (InvalidCredentialsException e) { message1 = e.getMessage(); }

        reset(authenticationManager);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new LockedException("any reason"));

        String message2 = null;
        try { authStrategy.login(validRequest); }
        catch (InvalidCredentialsException e) { message2 = e.getMessage(); }

        assertThat(message1).isEqualTo(message2).isEqualTo("Invalid email or password");
    }
}
