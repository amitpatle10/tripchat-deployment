package com.tripchat.service;

import com.tripchat.dto.request.RegisterRequest;
import com.tripchat.dto.response.AuthResponse;
import com.tripchat.exception.EmailAlreadyExistsException;
import com.tripchat.exception.UsernameAlreadyTakenException;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthServiceTest — unit tests for EmailPasswordAuthStrategy.register()
 *
 * Strategy: pure unit tests with Mockito mocks.
 * No Spring context loaded — fast, isolated, no DB or infra needed.
 *
 * @ExtendWith(MockitoExtension.class): initializes @Mock and @InjectMocks
 * without starting the full Spring container.
 *
 * Test naming convention: should<ExpectedBehavior>When<Condition>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailPasswordAuthStrategy — register()")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private EmailPasswordAuthStrategy authStrategy;

    private RegisterRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new RegisterRequest(
                "amit@test.com",
                "StrongPass1!",
                "amit_patle",
                "Amit"
        );
    }

    // ── Happy Path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should register successfully when all inputs are valid")
    void shouldRegisterSuccessfullyWhenAllInputsAreValid() {
        // Arrange
        when(userRepository.existsByEmailIgnoreCase("amit@test.com")).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase("amit_patle")).thenReturn(false);
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("hashed_password");

        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .passwordHash("hashed_password")
                .authProvider(AuthProvider.LOCAL)
                .build();

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken(any(User.class))).thenReturn("mock.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(86400000L);

        // Act
        AuthResponse response = authStrategy.register(validRequest);

        // Assert
        assertThat(response.getToken()).isEqualTo("mock.jwt.token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(86400000L);
        assertThat(response.getUser().getEmail()).isEqualTo("amit@test.com");
        assertThat(response.getUser().getUsername()).isEqualTo("amit_patle");

        verify(userRepository).save(any(User.class));
        verify(jwtService).generateToken(any(User.class));
    }

    @Test
    @DisplayName("should hash password before saving")
    void shouldHashPasswordBeforeSaving() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("bcrypt_hashed");

        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .passwordHash("bcrypt_hashed")
                .authProvider(AuthProvider.LOCAL)
                .build();

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        authStrategy.register(validRequest);

        // Verify raw password was NEVER saved — only the hash
        verify(passwordEncoder).encode("StrongPass1!");
        verify(userRepository).save(argThat(user ->
                "bcrypt_hashed".equals(user.getPasswordHash())
        ));
    }

    @Test
    @DisplayName("should normalize email and username to lowercase before saving")
    void shouldNormalizeEmailAndUsernameToLowercase() {
        RegisterRequest mixedCaseRequest = new RegisterRequest(
                "Amit@Test.COM",
                "StrongPass1!",
                "Amit_Patle",
                "Amit"
        );

        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .authProvider(AuthProvider.LOCAL)
                .build();

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        authStrategy.register(mixedCaseRequest);

        verify(userRepository).save(argThat(user ->
                "amit@test.com".equals(user.getEmail()) &&
                "amit_patle".equals(user.getUsername())
        ));
    }

    @Test
    @DisplayName("should set AuthProvider to LOCAL for email/password registration")
    void shouldSetAuthProviderToLocal() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .authProvider(AuthProvider.LOCAL)
                .build();

        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken(any(User.class))).thenReturn("token");

        authStrategy.register(validRequest);

        verify(userRepository).save(argThat(user ->
                AuthProvider.LOCAL == user.getAuthProvider()
        ));
    }

    // ── Conflict Cases ────────────────────────────────────────────────────────

    @Test
    @DisplayName("should throw EmailAlreadyExistsException when email is taken")
    void shouldThrowWhenEmailAlreadyExists() {
        when(userRepository.existsByEmailIgnoreCase("amit@test.com")).thenReturn(true);

        assertThatThrownBy(() -> authStrategy.register(validRequest))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("amit@test.com");

        // DB save must never be called
        verify(userRepository, never()).save(any());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("should throw UsernameAlreadyTakenException when username is taken")
    void shouldThrowWhenUsernameAlreadyTaken() {
        when(userRepository.existsByEmailIgnoreCase("amit@test.com")).thenReturn(false);
        when(userRepository.existsByUsernameIgnoreCase("amit_patle")).thenReturn(true);

        assertThatThrownBy(() -> authStrategy.register(validRequest))
                .isInstanceOf(UsernameAlreadyTakenException.class)
                .hasMessageContaining("amit_patle");

        verify(userRepository, never()).save(any());
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("should check email before username — email conflict takes priority")
    void shouldCheckEmailBeforeUsername() {
        // Both email and username are taken
        when(userRepository.existsByEmailIgnoreCase("amit@test.com")).thenReturn(true);

        // Email is checked first — UsernameAlreadyTaken should NOT be thrown
        assertThatThrownBy(() -> authStrategy.register(validRequest))
                .isInstanceOf(EmailAlreadyExistsException.class);

        // Username check never reached
        verify(userRepository, never()).existsByUsernameIgnoreCase(anyString());
    }
}
