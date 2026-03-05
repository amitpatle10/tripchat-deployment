package com.tripchat.messaging;

import com.tripchat.dto.messaging.ChatMessageEvent;
import com.tripchat.model.Outbox;
import com.tripchat.model.enums.OutboxStatus;
import com.tripchat.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * OutboxRelay — reads PENDING outbox records and publishes them to Kafka.
 *
 * Pattern: Transactional Outbox — this relay is the bridge between the
 * outbox table (durability guarantee) and Kafka (delivery mechanism).
 *
 * How it works:
 *   1. @Scheduled fires every 100ms (primary trigger)
 *   2. Queries outbox for PENDING records (batch of 100)
 *   3. For each record: publish to Kafka topic 'chat.messages'
 *   4. On success: mark PUBLISHED, set publishedAt
 *   5. On failure: increment retryCount; if >= 3, mark FAILED
 *
 * Partition key = groupId (String):
 *   All messages for the same group go to the same Kafka partition.
 *   Guarantees ordering per group — critical for chat.
 *
 * At-least-once delivery:
 *   If the app crashes after Kafka publish but before marking PUBLISHED,
 *   the relay will re-publish on restart. The Kafka consumer handles
 *   duplicates via ON CONFLICT (client_id) DO NOTHING.
 *
 * Nightly cleanup:
 *   Separate @Scheduled method deletes PUBLISHED records older than 7 days.
 *   Keeps the outbox table small regardless of message volume.
 *
 * Thread safety:
 *   @Scheduled runs on a single background thread by default.
 *   No concurrent relay execution — no race conditions on outbox records.
 *   If we need parallel relay threads in future, add a distributed lock (Redis SET NX).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final String TOPIC = "chat.messages";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 3;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    /**
     * Primary relay — runs every 100ms.
     * Processes PENDING outbox records and publishes to Kafka.
     */
    @Scheduled(fixedDelay = 100)
    @Transactional
    public void relay() {
        List<Outbox> pending = outboxRepository.findPendingRecords(
            OutboxStatus.PENDING,
            PageRequest.of(0, BATCH_SIZE)
        );

        if (pending.isEmpty()) return;

        log.debug("OutboxRelay: processing {} pending records", pending.size());

        for (Outbox outbox : pending) {
            publishToKafka(outbox);
        }
    }

    /**
     * Nightly cleanup — runs at 2am every day.
     * Deletes PUBLISHED outbox records older than 7 days.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        int deleted = outboxRepository.deletePublishedBefore(cutoff);
        log.info("OutboxRelay cleanup: deleted {} published records older than 7 days", deleted);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void publishToKafka(Outbox outbox) {
        try {
            ChatMessageEvent event = ChatMessageEvent.builder()
                    .outboxId(outbox.getId())
                    .messageId(UUID.randomUUID())       // pre-generate message PK
                    .groupId(outbox.getGroup().getId())
                    .senderId(outbox.getSenderId())
                    .content(outbox.getContent())
                    .clientId(outbox.getClientId())
                    .sentAt(outbox.getCreatedAt())
                    .build();

            // Partition key = groupId — all messages for same group → same partition → ordered
            kafkaTemplate.send(TOPIC, outbox.getGroup().getId().toString(), event).get();

            // Mark as published
            outbox.setStatus(OutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now());
            outboxRepository.save(outbox);

            log.debug("Published to Kafka: clientId={}, group={}", outbox.getClientId(), outbox.getGroup().getId());

        } catch (Exception e) {
            int retries = outbox.getRetryCount() + 1;
            outbox.setRetryCount(retries);

            if (retries >= MAX_RETRIES) {
                outbox.setStatus(OutboxStatus.FAILED);
                log.error("OutboxRelay: max retries reached for clientId={} — marking FAILED",
                        outbox.getClientId(), e);
            } else {
                log.warn("OutboxRelay: publish failed (attempt {}/{}) for clientId={}",
                        retries, MAX_RETRIES, outbox.getClientId(), e);
            }

            outboxRepository.save(outbox);
        }
    }
}
