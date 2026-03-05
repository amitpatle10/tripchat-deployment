package com.tripchat.repository;

import com.tripchat.model.Outbox;
import com.tripchat.model.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OutboxRepository — data access for the outbox relay table.
 *
 * Key query: findPendingRecords
 *   Relay polls this to find messages waiting to be published to Kafka.
 *   ORDER BY created_at ASC — process oldest first (FIFO per group order).
 *   LIMIT via Pageable — process in batches of 100 to avoid memory pressure.
 *   Covered by idx_outbox_status_created partial index on (status, created_at)
 *   WHERE status = 'PENDING' — only PENDING rows are indexed, keeping it small.
 *
 * Cleanup query: deletePublishedBefore
 *   Nightly job removes PUBLISHED records older than 7 days.
 *   @Modifying + @Transactional required for bulk delete via JPQL.
 */
@Repository
public interface OutboxRepository extends JpaRepository<Outbox, UUID> {

    /**
     * Relay polling query — fetch next batch of PENDING records.
     * Ordered oldest-first to maintain message ordering per group.
     */
    @Query("""
        SELECT o FROM Outbox o
        WHERE o.status = :status
        ORDER BY o.createdAt ASC
        """)
    List<Outbox> findPendingRecords(
        @Param("status") OutboxStatus status,
        org.springframework.data.domain.Pageable pageable
    );

    /**
     * Nightly cleanup — delete PUBLISHED records older than the given cutoff.
     * Keeps the outbox table small regardless of message volume.
     */
    @Modifying
    @Query("""
        DELETE FROM Outbox o
        WHERE o.status = 'PUBLISHED'
          AND o.publishedAt < :cutoff
        """)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);

    boolean existsByClientId(UUID clientId);
}
