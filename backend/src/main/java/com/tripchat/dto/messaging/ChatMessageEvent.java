package com.tripchat.dto.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * ChatMessageEvent — the Kafka message payload.
 *
 * This is the contract between the producer (OutboxRelay) and the
 * consumer (MessageKafkaConsumer). Both sides must use the same schema.
 *
 * Why a separate DTO and not the Outbox entity directly:
 *   Kafka messages are serialized to JSON. Sending JPA entities over Kafka
 *   risks serializing lazy-loaded proxies and leaking DB internals.
 *   A dedicated event DTO is a clean, stable contract decoupled from the DB schema.
 *
 * outboxId:
 *   Included so the consumer can correlate back to the outbox record
 *   if needed (e.g., for DLQ reprocessing).
 *
 * sentAt:
 *   The time the message was written to the outbox (user-perceived send time).
 *   Not the time Kafka received it — those can differ by the relay poll interval.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEvent {

    private UUID outboxId;
    private UUID messageId;     // pre-generated — consumer uses this as the message PK
    private UUID groupId;
    private UUID senderId;
    private String content;
    private UUID clientId;
    private Instant sentAt;
}
