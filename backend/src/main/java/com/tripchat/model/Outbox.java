package com.tripchat.model;

import com.tripchat.model.enums.OutboxStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entity — transient relay table for the cold path pipeline.
 *
 * Pattern: Transactional Outbox Pattern.
 *   Problem: We can't atomically write to DB and publish to Kafka in one step.
 *   Solution: Write to outbox table first (same DB transaction), then a
 *   background relay reads pending records and publishes to Kafka reliably.
 *   This guarantees at-least-once Kafka delivery even if the app crashes.
 *
 * group (ManyToOne, ON DELETE CASCADE):
 *   If group is deleted, outbox rows are cleaned up automatically.
 *   Without this: relay would publish orphaned messages → Kafka consumer
 *   would fail FK check on messages table → DLQ noise.
 *
 * senderId (UUID, no FK):
 *   Relay only needs the value to build the Kafka payload.
 *   The Kafka consumer handles SET NULL on the messages table side.
 *   No FK = no constraint overhead on every insert.
 *
 * clientId (UNIQUE):
 *   Prevents the same message from entering the outbox twice
 *   (e.g., duplicate WebSocket delivery from client retry).
 *
 * retryCount:
 *   Incremented on each failed Kafka publish attempt.
 *   After 3 failures → status = FAILED → sent to DLQ.
 *
 * Lifecycle: PENDING → PUBLISHED → (cleanup job deletes after 7 days)
 *                    → FAILED    → (manual inspection via DLQ)
 */
@Entity
@Table(
    name = "outbox",
    indexes = {
        // Partial index — relay polling query: WHERE status = 'PENDING'
        // Only PENDING rows are indexed; PUBLISHED rows fall out automatically
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    // FK with CASCADE — group deleted → outbox rows cleaned up
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Group group;

    // No FK — relay only needs the UUID value for Kafka payload
    @Column(nullable = false, updatable = false)
    private UUID senderId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Client-generated UUID — dedup at outbox write time
    @Column(nullable = false, unique = true, updatable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    // How many Kafka publish attempts have failed
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Set by relay when Kafka publish succeeds
    @Column
    private Instant publishedAt;
}
