package com.tripchat.model;

import com.tripchat.model.enums.AuthProvider;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * User entity — maps to the `users` table.
 *
 * Design decisions:
 *
 * id — UUID over auto-increment Long:
 *   Non-guessable (prevents /users/1, /users/2 enumeration attacks),
 *   safe to expose in URLs, distributed-safe (no central sequence needed).
 *
 * username vs displayName:
 *   username   = unique identity handle (@amit_patle), immutable after set.
 *   displayName = human-readable name shown in chat, free text, not unique.
 *   Mirrors Twitter/Telegram pattern — separates identity from presentation.
 *
 * passwordHash — nullable:
 *   NULL for GOOGLE auth users — Google owns their credential.
 *   Always set for LOCAL auth users.
 *
 * isActive — soft delete:
 *   Never hard-delete users — messages still reference them.
 *   Preserves referential integrity + audit trail + recovery option.
 *
 * Auditing (@CreatedDate, @LastModifiedDate):
 *   Managed by Spring Data JPA AuditingEntityListener.
 *   Requires @EnableJpaAuditing on config class.
 *   Instant over LocalDateTime — timezone-safe, UTC always.
 */
@Entity
@Table(
    name = "users",
    indexes = {
        // Case-insensitive unique indexes — "Amit" and "amit" treated as same.
        // LOWER() function index defined in migration, not here.
        // Hibernate validates existence but doesn't create function-based indexes.
        @Index(name = "idx_users_email", columnList = "email"),
        @Index(name = "idx_users_username", columnList = "username")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // Identity handle — unique, shown as @username in UI
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    // Human-readable name in chat — not unique, changeable
    @Column(nullable = false, length = 30)
    private String displayName;

    // Null for GOOGLE provider — Google owns the credential
    @Column(length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // Null for LOCAL — stores Google's user ID for GOOGLE provider
    @Column(length = 255)
    private String providerId;

    @Column(nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
