package com.tripchat.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * InviteCodeGenerator — generates cryptographically random 8-char invite codes.
 *
 * Character set: A-Z + 2-9, excluding O/0 and I/1 to avoid visual confusion
 * when codes are read aloud or typed manually.
 *   32 characters × 8 positions = 32^8 ≈ 1 trillion combinations.
 *   At 1000 groups, collision probability is negligible.
 *
 * SecureRandom over Math.random():
 *   Math.random() is predictable — attacker can enumerate codes.
 *   SecureRandom uses OS entropy source — codes are unpredictable.
 *   This matters because the invite code IS the access control mechanism.
 *
 * @Component (Singleton): one SecureRandom instance shared across all calls.
 *   SecureRandom is thread-safe — safe to share.
 *   Avoids re-seeding overhead on every code generation.
 */
@Component
public class InviteCodeGenerator {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
