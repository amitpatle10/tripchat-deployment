package com.tripchat.messaging;

import com.tripchat.dto.messaging.ChatMessageEvent;
import com.tripchat.dto.response.MessageResponse;
import com.tripchat.model.Group;
import com.tripchat.model.Message;
import com.tripchat.model.User;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.MessageRepository;
import com.tripchat.repository.UserRepository;
import com.tripchat.service.MessageCacheService;
import com.tripchat.service.UnreadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * MessageKafkaConsumer — cold path: persists messages to DB and warms Redis cache.
 *
 * This consumer is the only writer to the messages table.
 * The hot path (MessageService) only writes to the outbox table.
 *
 * Manual acknowledgment (acks):
 *   We commit the Kafka offset only AFTER the DB write succeeds.
 *   If the app crashes between DB write and offset commit — the message is
 *   re-delivered (at-least-once). The clientId UNIQUE constraint handles the
 *   duplicate gracefully (INSERT ... ON CONFLICT DO NOTHING equivalent via
 *   existsByClientId check before insert).
 *   We never commit before processing — that would risk losing messages.
 *
 * Consumer group: tripchat-group (configured in application.yml)
 *   With 3 partitions and 1 server, this consumer handles all 3 partitions.
 *   With 3 servers, each handles 1 partition — linear horizontal scaling.
 *
 * Idempotency:
 *   Check existsByClientId before insert. If already exists — skip insert,
 *   still commit offset. Message was already persisted (duplicate delivery).
 *
 * Redis write-through:
 *   After DB persist, write to Redis sorted set (score = epoch ms).
 *   Cache is always populated from the consumer — never stale.
 *   Trim to 50 entries per group after each write.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageKafkaConsumer {

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final MessageCacheService messageCacheService;
    private final UnreadService unreadService;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(
        topics = "chat.messages",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
        ChatMessageEvent event = record.value();

        log.debug("Kafka consumer received: clientId={}, group={}", event.getClientId(), event.getGroupId());

        try {
            // Idempotency check — skip if already persisted (duplicate delivery)
            if (messageRepository.existsByClientId(event.getClientId())) {
                log.debug("Duplicate clientId {} — skipping DB insert", event.getClientId());
                ack.acknowledge();
                return;
            }

            // Load references — group and sender must exist
            Optional<Group> groupOpt = groupRepository.findById(event.getGroupId());
            if (groupOpt.isEmpty()) {
                log.warn("Group {} not found — message discarded (group may have been deleted)", event.getGroupId());
                ack.acknowledge();
                return;
            }

            // sender can be null — user may have deleted their account (SET NULL behaviour)
            Optional<User> senderOpt = userRepository.findById(event.getSenderId());

            // Do NOT set id — let JPA generate it via @GeneratedValue.
            // If we set id explicitly, Spring Data's save() sees a non-null id,
            // calls merge() (UPDATE) instead of persist() (INSERT), and
            // throws StaleObjectStateException because the row doesn't exist yet.
            // clientId UNIQUE constraint is the idempotency key, not messageId.
            Message message = Message.builder()
                    .group(groupOpt.get())
                    .sender(senderOpt.orElse(null))     // null = deleted user
                    .content(event.getContent())
                    .clientId(event.getClientId())
                    .build();

            messageRepository.save(message);

            // Write-through cache — add to Redis sorted set (score = epoch ms)
            messageCacheService.cacheMessage(message);

            // Broadcast the confirmed message (with its real DB-assigned UUID) to all
            // group members. The hot path already broadcast with id=null — this second
            // broadcast carries the real id so the frontend can stop dimming the bubble.
            // addMessage on the client matches by clientId and replaces the null-id entry.
            messagingTemplate.convertAndSend(
                "/topic/groups/" + event.getGroupId(),
                MessageResponse.from(message)
            );

            // Increment unread count for every group member except the sender
            // HINCRBY is atomic — safe under concurrent message delivery
            groupMemberRepository.findByGroup(groupOpt.get()).stream()
                    .map(gm -> gm.getUser().getId())
                    .filter(memberId -> !memberId.equals(event.getSenderId()))
                    .forEach(memberId -> unreadService.increment(memberId, event.getGroupId()));

            log.debug("Message persisted and confirmed: id={}, group={}", message.getId(), event.getGroupId());

            // Commit offset only after successful DB write
            ack.acknowledge();

        } catch (Exception e) {
            // Do NOT acknowledge — Kafka will re-deliver this message
            log.error("Failed to process Kafka message: clientId={}", event.getClientId(), e);
            // Spring Kafka will retry based on the container's error handler config
        }
    }
}
