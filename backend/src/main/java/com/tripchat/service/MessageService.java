package com.tripchat.service;

import com.tripchat.dto.request.SendMessageRequest;
import com.tripchat.dto.response.MessageResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.Outbox;
import com.tripchat.model.User;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.OutboxRepository;
import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * MessageService — handles the hot path of message sending.
 *
 * Hot path (this class): ~10ms, user waits for this.
 *   1. Validate sender is a member of the group
 *   2. Write to outbox table (durable, status=PENDING)
 *   3. Broadcast via STOMP to all online group members
 *   4. Return — user gets ack
 *
 * Cold path (OutboxRelay + MessageKafkaConsumer): async, user doesn't wait.
 *   Outbox relay picks up PENDING records → publishes to Kafka →
 *   consumer persists to messages table + writes to Redis cache.
 *
 * Pattern: Separation of Concerns — this service owns only the hot path.
 * Persistence and caching are delegated to the cold path asynchronously.
 *
 * SimpMessagingTemplate:
 *   Spring's STOMP broadcasting API. convertAndSend() serializes the DTO
 *   to JSON and pushes it to all sessions subscribed to the destination.
 *   Thread-safe — can be called from any thread.
 *
 * Idempotency:
 *   If outbox already has a record with the same clientId (client retry),
 *   the UNIQUE constraint throws DataIntegrityViolationException.
 *   We catch and silently ignore it — the message was already queued.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageService {

    private final OutboxRepository outboxRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Hot path — called by the WebSocket @MessageMapping handler.
     * Writes to outbox and broadcasts optimistically.
     * Returns the MessageResponse so the handler can confirm to the sender.
     */
    @Transactional
    public MessageResponse sendMessage(String senderEmail, UUID groupId, SendMessageRequest request) {

        User sender = loadUser(senderEmail);
        Group group = loadActiveGroup(groupId);
        validateMembership(group, sender);

        // Idempotency guard — if client retried with the same clientId, skip
        if (outboxRepository.existsByClientId(request.getClientId())) {
            log.debug("Duplicate clientId {} — skipping outbox write", request.getClientId());
            // Build and return a response without re-broadcasting
            return buildOptimisticResponse(sender, group, request);
        }

        // Write to outbox — durable, status=PENDING
        // Cold path relay will pick this up and publish to Kafka
        Outbox outbox = Outbox.builder()
                .group(group)
                .senderId(sender.getId())
                .content(request.getContent())
                .clientId(request.getClientId())
                .build();
        outboxRepository.save(outbox);

        // Build optimistic response — no message ID yet (not persisted to messages table)
        // The Kafka consumer will persist it; clients reconcile via clientId
        MessageResponse response = buildOptimisticResponse(sender, group, request);

        // Broadcast to all online group members via STOMP
        // Pattern: Observer — all subscribers to this topic are notified
        String destination = "/topic/groups/" + groupId;
        messagingTemplate.convertAndSend(destination, response);

        log.debug("Message queued and broadcast: clientId={}, group={}", request.getClientId(), groupId);

        return response;
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private MessageResponse buildOptimisticResponse(User sender, Group group, SendMessageRequest request) {
        return MessageResponse.builder()
                .id(null)                               // not yet persisted — null until Kafka consumer confirms
                .groupId(group.getId())
                .senderId(sender.getId())
                .senderUsername(sender.getUsername())
                .senderDisplayName(sender.getDisplayName())
                .content(request.getContent())
                .clientId(request.getClientId())
                .createdAt(Instant.now())
                .deleted(false)
                .build();
    }

    private User loadUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    private Group loadActiveGroup(UUID groupId) {
        return groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));
    }

    private void validateMembership(Group group, User user) {
        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new NotMemberException();
        }
    }
}
