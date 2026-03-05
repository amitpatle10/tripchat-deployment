package com.tripchat.service;

import com.tripchat.dto.request.SendMessageRequest;
import com.tripchat.dto.response.MessageResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.Outbox;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.model.enums.OutboxStatus;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.OutboxRepository;
import com.tripchat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MessageServiceTest — unit tests for the hot path message sending logic.
 *
 * Hot path under test:
 *   1. Validate sender is a group member
 *   2. Write to outbox table (status = PENDING)
 *   3. Broadcast via STOMP to group topic
 *   4. Return optimistic MessageResponse
 *
 * All dependencies are mocked — no DB, no Redis, no Kafka, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService")
class MessageServiceTest {

    @Mock private OutboxRepository outboxRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private MessageService messageService;

    private User sender;
    private Group group;
    private SendMessageRequest request;

    @BeforeEach
    void setUp() {
        sender = User.builder()
                .id(UUID.randomUUID())
                .email("amit@test.com")
                .username("amit_patle")
                .displayName("Amit")
                .authProvider(AuthProvider.LOCAL)
                .isActive(true)
                .build();

        group = Group.builder()
                .id(UUID.randomUUID())
                .name("Trip to Goa")
                .createdBy(sender)
                .inviteCode("TRIP1234")
                .isActive(true)
                .build();

        request = new SendMessageRequest();
        setField(request, "clientId", UUID.randomUUID());
        setField(request, "content", "Hello everyone!");
    }

    // ── sendMessage() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendMessage()")
    class SendMessage {

        @Test
        @DisplayName("should save outbox record with PENDING status on success")
        void shouldSaveOutboxWithPendingStatus() {
            arrangeMemberSend();

            messageService.sendMessage("amit@test.com", group.getId(), request);

            ArgumentCaptor<Outbox> captor = ArgumentCaptor.forClass(Outbox.class);
            verify(outboxRepository).save(captor.capture());

            Outbox saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(saved.getContent()).isEqualTo("Hello everyone!");
            assertThat(saved.getClientId()).isEqualTo(request.getClientId());
            assertThat(saved.getSenderId()).isEqualTo(sender.getId());
        }

        @Test
        @DisplayName("should broadcast to correct STOMP destination")
        void shouldBroadcastToCorrectDestination() {
            arrangeMemberSend();

            messageService.sendMessage("amit@test.com", group.getId(), request);

            String expectedDestination = "/topic/groups/" + group.getId();
            verify(messagingTemplate).convertAndSend(eq(expectedDestination), any(MessageResponse.class));
        }

        @Test
        @DisplayName("should return response with sender info and content")
        void shouldReturnOptimisticResponse() {
            arrangeMemberSend();

            MessageResponse response = messageService.sendMessage("amit@test.com", group.getId(), request);

            assertThat(response.getContent()).isEqualTo("Hello everyone!");
            assertThat(response.getSenderUsername()).isEqualTo("amit_patle");
            assertThat(response.getSenderDisplayName()).isEqualTo("Amit");
            assertThat(response.getSenderId()).isEqualTo(sender.getId());
            assertThat(response.getGroupId()).isEqualTo(group.getId());
            assertThat(response.getClientId()).isEqualTo(request.getClientId());
            assertThat(response.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("should return null id — message not yet persisted to messages table")
        void shouldReturnNullIdBeforePersistence() {
            arrangeMemberSend();

            MessageResponse response = messageService.sendMessage("amit@test.com", group.getId(), request);

            // id is null until Kafka consumer persists to messages table
            assertThat(response.getId()).isNull();
        }

        @Test
        @DisplayName("should throw NotMemberException when sender is not a group member")
        void shouldThrowWhenNotMember() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(sender));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, sender)).thenReturn(false);

            assertThatThrownBy(() -> messageService.sendMessage("amit@test.com", group.getId(), request))
                    .isInstanceOf(NotMemberException.class);

            // Outbox must NOT be written if membership check fails
            verify(outboxRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        }

        @Test
        @DisplayName("should throw GroupNotFoundException when group does not exist")
        void shouldThrowWhenGroupNotFound() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(sender));
            when(groupRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> messageService.sendMessage("amit@test.com", UUID.randomUUID(), request))
                    .isInstanceOf(GroupNotFoundException.class);

            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw GroupNotFoundException when group is inactive")
        void shouldThrowWhenGroupInactive() {
            Group inactiveGroup = Group.builder()
                    .id(UUID.randomUUID())
                    .name("Old Group")
                    .createdBy(sender)
                    .inviteCode("OLDCODE1")
                    .isActive(false)
                    .build();

            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(sender));
            when(groupRepository.findById(inactiveGroup.getId())).thenReturn(Optional.of(inactiveGroup));

            assertThatThrownBy(() -> messageService.sendMessage("amit@test.com", inactiveGroup.getId(), request))
                    .isInstanceOf(GroupNotFoundException.class);
        }

        @Test
        @DisplayName("should throw UsernameNotFoundException when sender email not found")
        void shouldThrowWhenSenderNotFound() {
            when(userRepository.findByEmailIgnoreCase("unknown@test.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> messageService.sendMessage("unknown@test.com", group.getId(), request))
                    .isInstanceOf(UsernameNotFoundException.class);
        }

        @Test
        @DisplayName("should skip outbox write and not broadcast on duplicate clientId")
        void shouldSkipOnDuplicateClientId() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(sender));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, sender)).thenReturn(true);
            when(outboxRepository.existsByClientId(request.getClientId())).thenReturn(true);

            MessageResponse response = messageService.sendMessage("amit@test.com", group.getId(), request);

            // Duplicate — outbox not written, no broadcast
            verify(outboxRepository, never()).save(any());
            verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));

            // Response still returned — sender gets their own optimistic ack
            assertThat(response).isNotNull();
            assertThat(response.getContent()).isEqualTo("Hello everyone!");
        }

        @Test
        @DisplayName("should set outbox group and senderId correctly")
        void shouldSetOutboxGroupAndSenderId() {
            arrangeMemberSend();

            messageService.sendMessage("amit@test.com", group.getId(), request);

            verify(outboxRepository).save(argThat(outbox ->
                outbox.getGroup().getId().equals(group.getId()) &&
                outbox.getSenderId().equals(sender.getId())
            ));
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void arrangeMemberSend() {
        when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(sender));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupAndUser(group, sender)).thenReturn(true);
        when(outboxRepository.existsByClientId(any())).thenReturn(false);
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Reflection helper — sets private fields on request DTOs with no setters. */
    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
