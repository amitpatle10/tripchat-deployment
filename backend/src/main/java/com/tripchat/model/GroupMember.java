package com.tripchat.model;

import com.tripchat.model.enums.MemberRole;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * GroupMember entity — junction table between User and Group.
 *
 * Models the many-to-many relationship: one user can be in many groups,
 * one group has many users.
 *
 * Why UUID PK instead of composite key (group_id, user_id):
 *   Composite keys in JPA require @EmbeddedId or @IdClass — more boilerplate.
 *   UUID PK is simpler. Uniqueness still enforced via @UniqueConstraint.
 *   Tradeoff: slight extra storage (16 bytes) vs code clarity. Worth it.
 *
 * FetchType.LAZY on both associations:
 *   Loading a membership record doesn't always need the full Group or User object.
 *   Lazy loading prevents N+1 queries — only fetched when explicitly accessed.
 *
 * joinedAt:
 *   Used for "oldest member becomes ADMIN" logic in future,
 *   and for showing "joined X days ago" in the UI.
 */
@Entity
@Table(
    name = "group_members",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_group_members_group_user",
        columnNames = {"group_id", "user_id"}
    ),
    indexes = {
        @Index(name = "idx_group_members_user", columnList = "user_id"),
        @Index(name = "idx_group_members_group", columnList = "group_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MemberRole role = MemberRole.MEMBER;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant joinedAt;
}
