package com.tripchat.messaging;

import com.tripchat.dto.messaging.ChatMessageEvent;
import com.tripchat.model.Group;
import com.tripchat.model.Outbox;
import com.tripchat.model.enums.OutboxStatus;
import com.tripchat.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * OutboxRelayTest — unit tests for the cold path relay logic.
 *
 * Scenarios covered:
 *   - Happy path: PENDING → PUBLISHED
 *   - Empty outbox: no-op
 *   - Kafka failure: retry count incremented
 *   - Max retries exceeded: status set to FAILED
 *   - Correct partition key (groupId) used on Kafka publish
 *   - publishedAt set on success
 *   - Nightly cleanup delegates to repository correctly
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxRelay")
class OutboxRelayTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    @InjectMocks
    private OutboxRelay outboxRelay;

    private Group group;
    private Outbox pendingOutbox;

    @BeforeEach
    void setUp() {
        group = Group.builder()
                .id(UUID.randomUUID())
                .name("Trip to Goa")
                .inviteCode("TRIP1234")
                .isActive(true)
                .build();

        pendingOutbox = Outbox.builder()
                .id(UUID.randomUUID())
                .group(group)
                .senderId(UUID.randomUUID())
                .content("Hello!")
                .clientId(UUID.randomUUID())
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .createdAt(Instant.now())
                .build();
    }

    // ── relay() ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("relay()")
    class Relay {

        @Test
        @DisplayName("should mark outbox record as PUBLISHED on successful Kafka send")
        void shouldMarkPublishedOnSuccess() throws Exception {
            arrangePendingRecords(List.of(pendingOutbox));
            arrangeKafkaSuccess();

            outboxRelay.relay();

            ArgumentCaptor<Outbox> captor = ArgumentCaptor.forClass(Outbox.class);
            verify(outboxRepository).save(captor.capture());

            Outbox saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(saved.getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("should set publishedAt timestamp when published successfully")
        void shouldSetPublishedAt() throws Exception {
            Instant before = Instant.now();
            arrangePendingRecords(List.of(pendingOutbox));
            arrangeKafkaSuccess();

            outboxRelay.relay();

            verify(outboxRepository).save(argThat(o ->
                o.getPublishedAt() != null && !o.getPublishedAt().isBefore(before)
            ));
        }

        @Test
        @DisplayName("should use groupId as Kafka partition key")
        void shouldUseGroupIdAsPartitionKey() throws Exception {
            arrangePendingRecords(List.of(pendingOutbox));
            arrangeKafkaSuccess();

            outboxRelay.relay();

            verify(kafkaTemplate).send(
                eq("chat.messages"),
                eq(group.getId().toString()),
                any(ChatMessageEvent.class)
            );
        }

        @Test
        @DisplayName("should include all required fields in Kafka event payload")
        void shouldBuildCorrectKafkaEvent() throws Exception {
            arrangePendingRecords(List.of(pendingOutbox));
            arrangeKafkaSuccess();

            outboxRelay.relay();

            ArgumentCaptor<ChatMessageEvent> eventCaptor = ArgumentCaptor.forClass(ChatMessageEvent.class);
            verify(kafkaTemplate).send(any(), any(), eventCaptor.capture());

            ChatMessageEvent event = eventCaptor.getValue();
            assertThat(event.getGroupId()).isEqualTo(group.getId());
            assertThat(event.getSenderId()).isEqualTo(pendingOutbox.getSenderId());
            assertThat(event.getContent()).isEqualTo("Hello!");
            assertThat(event.getClientId()).isEqualTo(pendingOutbox.getClientId());
            assertThat(event.getOutboxId()).isEqualTo(pendingOutbox.getId());
            assertThat(event.getMessageId()).isNotNull();  // pre-generated UUID
            assertThat(event.getSentAt()).isEqualTo(pendingOutbox.getCreatedAt());
        }

        @Test
        @DisplayName("should do nothing when no PENDING records exist")
        void shouldDoNothingWhenNoPendingRecords() {
            arrangePendingRecords(List.of());

            outboxRelay.relay();

            verify(kafkaTemplate, never()).send(any(), any(), any());
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("should increment retryCount on Kafka failure")
        void shouldIncrementRetryCountOnFailure() {
            arrangePendingRecords(List.of(pendingOutbox));
            arrangeKafkaFailure();

            outboxRelay.relay();

            verify(outboxRepository).save(argThat(o ->
                o.getRetryCount() == 1 && o.getStatus() == OutboxStatus.PENDING
            ));
        }

        @Test
        @DisplayName("should mark FAILED after 3 failed attempts")
        void shouldMarkFailedAfterMaxRetries() {
            Outbox twoRetries = Outbox.builder()
                    .id(UUID.randomUUID())
                    .group(group)
                    .senderId(UUID.randomUUID())
                    .content("Hello!")
                    .clientId(UUID.randomUUID())
                    .status(OutboxStatus.PENDING)
                    .retryCount(2)   // already failed twice — this is the 3rd attempt
                    .createdAt(Instant.now())
                    .build();

            arrangePendingRecords(List.of(twoRetries));
            arrangeKafkaFailure();

            outboxRelay.relay();

            verify(outboxRepository).save(argThat(o ->
                o.getStatus() == OutboxStatus.FAILED && o.getRetryCount() == 3
            ));
        }

        @Test
        @DisplayName("should keep status PENDING when retryCount is below max")
        void shouldKeepPendingWhenBelowMaxRetries() {
            arrangePendingRecords(List.of(pendingOutbox)); // retryCount = 0
            arrangeKafkaFailure();

            outboxRelay.relay();

            verify(outboxRepository).save(argThat(o ->
                o.getStatus() == OutboxStatus.PENDING && o.getRetryCount() == 1
            ));
        }

        @Test
        @DisplayName("should process all records in the batch")
        void shouldProcessAllRecordsInBatch() throws Exception {
            Outbox second = Outbox.builder()
                    .id(UUID.randomUUID()).group(group).senderId(UUID.randomUUID())
                    .content("Second!").clientId(UUID.randomUUID())
                    .status(OutboxStatus.PENDING).retryCount(0).createdAt(Instant.now())
                    .build();

            arrangePendingRecords(List.of(pendingOutbox, second));
            arrangeKafkaSuccess();

            outboxRelay.relay();

            verify(kafkaTemplate, times(2)).send(any(), any(), any());
            verify(outboxRepository, times(2)).save(any());
        }
    }

    // ── cleanup() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanup()")
    class Cleanup {

        @Test
        @DisplayName("should delete PUBLISHED records older than 7 days")
        void shouldDeleteOldPublishedRecords() {
            when(outboxRepository.deletePublishedBefore(any())).thenReturn(42);

            outboxRelay.cleanup();

            ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(outboxRepository).deletePublishedBefore(cutoffCaptor.capture());

            // Cutoff should be approximately 7 days ago
            Instant cutoff = cutoffCaptor.getValue();
            Instant sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 60 * 60);
            assertThat(cutoff).isBefore(Instant.now());
            assertThat(cutoff).isAfter(sevenDaysAgo.minusSeconds(5)); // 5s tolerance
        }

        @Test
        @DisplayName("should not throw when no records to delete")
        void shouldNotThrowWhenNothingToDelete() {
            when(outboxRepository.deletePublishedBefore(any())).thenReturn(0);

            outboxRelay.cleanup(); // no exception
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void arrangePendingRecords(List<Outbox> records) {
        when(outboxRepository.findPendingRecords(eq(OutboxStatus.PENDING), any(Pageable.class)))
            .thenReturn(records);
    }

    @SuppressWarnings("unchecked")
    private void arrangeKafkaSuccess() {
        CompletableFuture<SendResult<String, ChatMessageEvent>> future =
            CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void arrangeKafkaFailure() {
        when(kafkaTemplate.send(any(), any(), any()))
            .thenThrow(new RuntimeException("Kafka broker unavailable"));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
}
