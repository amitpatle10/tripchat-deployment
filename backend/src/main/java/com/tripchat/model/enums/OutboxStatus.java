package com.tripchat.model.enums;

/**
 * OutboxStatus — lifecycle states of an outbox record.
 *
 * State machine:
 *   PENDING   → written by WebSocket handler on message receipt
 *   PUBLISHED → relay successfully published to Kafka
 *   FAILED    → 3 retries exhausted; message sent to Kafka DLQ
 *
 * Stored as STRING in DB (not ordinal) — safe against enum reordering.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
