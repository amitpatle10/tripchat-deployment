package com.tripchat.repository;

import com.tripchat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MessageRepository — data access for chat messages.
 *
 * Pagination strategy: cursor-based, not offset.
 *   Offset pagination (OFFSET 10000 LIMIT 50) scans and discards 10,000 rows
 *   before returning results — gets slower the deeper you paginate.
 *   Cursor-based uses (created_at, id) as a position marker — jumps directly
 *   to the right position in the index. O(log N) regardless of depth.
 *
 * Composite cursor (createdAt, id):
 *   Two messages can share the same millisecond timestamp.
 *   Adding id (UUID) as a tiebreaker makes the cursor always unique
 *   and the sort order deterministic.
 *
 * JOIN FETCH sender:
 *   Prevents N+1 queries — loads sender info in the same query as messages.
 *   Without this, accessing message.getSender() for 50 messages = 50 extra queries.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /**
     * First page — no cursor, get the latest N messages for a group.
     * WHERE deleted_at IS NULL — soft delete filter.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.group.id = :groupId
          AND m.deletedAt IS NULL
        ORDER BY m.createdAt DESC, m.id DESC
        """)
    List<Message> findLatestByGroup(
        @Param("groupId") UUID groupId,
        org.springframework.data.domain.Pageable pageable
    );

    /**
     * Subsequent pages — cursor is (createdAt, id) of the last seen message.
     * Fetches messages older than the cursor position.
     */
    @Query("""
        SELECT m FROM Message m
        JOIN FETCH m.sender
        WHERE m.group.id = :groupId
          AND m.deletedAt IS NULL
          AND (m.createdAt < :cursorTime
               OR (m.createdAt = :cursorTime AND m.id < :cursorId))
        ORDER BY m.createdAt DESC, m.id DESC
        """)
    List<Message> findByGroupBeforeCursor(
        @Param("groupId") UUID groupId,
        @Param("cursorTime") Instant cursorTime,
        @Param("cursorId") UUID cursorId,
        org.springframework.data.domain.Pageable pageable
    );

    /**
     * Check if a message with this clientId already exists.
     * Used for idempotency guard at the consumer side.
     */
    boolean existsByClientId(UUID clientId);

    /**
     * Find a message by its ID scoped to a specific group.
     * Used by the delete endpoint — prevents cross-group access.
     */
    Optional<Message> findByIdAndGroup_Id(UUID id, UUID groupId);
}
