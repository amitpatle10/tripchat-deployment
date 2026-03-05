package com.tripchat.security.jwt;

import com.tripchat.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JwtService — JWT issuance and validation.
 *
 * Responsibilities:
 *   1. Generate a signed JWT for a given user (on register + login)
 *   2. Validate an incoming JWT (in JwtAuthFilter on every request)
 *   3. Extract claims (email, userId) from a valid token
 *
 * Shared across ALL AuthStrategy implementations — regardless of how a user
 * authenticates (email/password or Google), they get the same JWT format.
 * This is the unification point in the auth flow.
 *
 * Algorithm: HS256 (HMAC-SHA256)
 *   Symmetric — same secret key signs and verifies.
 *   Tradeoff vs RS256 (asymmetric): simpler (one key), but the key must be
 *   kept secret on our server. RS256 would let others verify without the secret.
 *   HS256 is correct for a monolith where we both issue and verify tokens.
 *
 * Claims stored in token:
 *   sub (subject) = email — standard JWT claim, used as principal identifier
 *   userId        = UUID  — avoid DB lookup just to get the ID
 *   username      = handle — for display without DB lookup
 *
 * Using jjwt 0.12.x API (note: differs from 0.11.x — builder methods renamed)
 */
@Service
@Slf4j
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationMs;

    /**
     * Generate a signed JWT for the given user.
     * Called once after successful registration or login.
     */
    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getEmail())                           // standard 'sub' claim
                .claim("userId", user.getId().toString())           // custom claim
                .claim("username", user.getUsername())              // custom claim
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Extract all claims from a token.
     * Throws JwtException if token is expired, malformed, or signature invalid.
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Extract the subject (email) from a token. */
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    /** Returns true if token is valid and not expired. */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /**
     * Decode the Base64-encoded secret and create an HMAC-SHA key.
     * Keys.hmacShaKeyFor() ensures the key meets the minimum length for HS256.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
