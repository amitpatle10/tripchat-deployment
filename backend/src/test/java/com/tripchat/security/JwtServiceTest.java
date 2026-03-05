package com.tripchat.security;

import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtServiceTest — unit tests for JWT generation and validation.
 *
 * No Spring context — ReflectionTestUtils injects @Value fields directly.
 * This lets us test JwtService in complete isolation.
 *
 * Test secret: valid Base64-encoded 256-bit key (required for HS256).
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    // Valid Base64-encoded 256-bit key for HS256
    private static final String TEST_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long TEST_EXPIRATION = 3600000L; // 1 hour

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtService, "expirationMs", TEST_EXPIRATION);

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .authProvider(AuthProvider.LOCAL)
                .build();
    }

    @Test
    @DisplayName("should generate a non-null token for a valid user")
    void shouldGenerateTokenForValidUser() {
        String token = jwtService.generateToken(testUser);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should extract correct email from generated token")
    void shouldExtractCorrectEmailFromToken() {
        String token = jwtService.generateToken(testUser);
        String extractedEmail = jwtService.extractEmail(token);
        assertThat(extractedEmail).isEqualTo("amit@test.com");
    }

    @Test
    @DisplayName("should validate a freshly generated token as valid")
    void shouldValidateFreshToken() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    @DisplayName("should reject a tampered token")
    void shouldRejectTamperedToken() {
        String token = jwtService.generateToken(testUser);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    @DisplayName("should reject an expired token")
    void shouldRejectExpiredToken() {
        // Set expiration to -1ms (already expired)
        ReflectionTestUtils.setField(jwtService, "expirationMs", -1000L);
        String expiredToken = jwtService.generateToken(testUser);
        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("should reject a malformed token")
    void shouldRejectMalformedToken() {
        assertThat(jwtService.isTokenValid("not.a.valid.jwt")).isFalse();
    }

    @Test
    @DisplayName("should embed userId and username as custom claims")
    void shouldEmbedCustomClaims() {
        String token = jwtService.generateToken(testUser);
        var claims = jwtService.extractAllClaims(token);

        assertThat(claims.get("userId", String.class))
                .isEqualTo(testUser.getId().toString());
        assertThat(claims.get("username", String.class))
                .isEqualTo("amit_patle");
    }
}
