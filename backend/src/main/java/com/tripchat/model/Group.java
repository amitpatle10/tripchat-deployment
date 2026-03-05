package com.tripchat.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Group entity — maps to the `chat_groups` table.
 *
 * Table named `chat_groups` (not `groups`) because GROUP is a reserved
 * keyword in SQL — avoids quoting issues across DB dialects.
 *
 * invite_code:
 *   8-char uppercase alphanumeric, globally unique.
 *   Generated on creation, regeneratable by ADMIN.
 *   This IS the join mechanism — no public group listing exposed.
 *   Level 2 design: fixed code + regeneration. No expiry in Phase 1.
 *
 * created_by → User:
 *   FetchType.LAZY — we don't load the full User object unless explicitly
 *   accessed. Prevents unnecessary DB joins on every group fetch.
 *   Tradeoff: must access within a transaction or use DTO projection.
 *
 * is_active — soft delete:
 *   Same reasoning as User — preserves message history, audit trail.
 */
@Entity
@Table(
    name = "chat_groups",
    indexes = {
        @Index(name = "idx_groups_invite_code", columnList = "inviteCode", unique = true),
        @Index(name = "idx_groups_created_by", columnList = "created_by")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String name;

    // Optional — nullable, up to 500 chars
    @Column(length = 500)
    private String description;

    // Who created this group — always the first ADMIN
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // 8-char unique join code — shared out-of-band by members
    @Column(nullable = false, unique = true, length = 8)
    private String inviteCode;

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
