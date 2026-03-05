package com.tripchat.service;

import com.tripchat.dto.response.TypingEvent;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TypingService")
class TypingServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private TypingService typingService;

    private User alice;
    private Group group;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(UUID.randomUUID()).email("alice@test.com")
                .username("alice").displayName("Alice")
                .authProvider(AuthProvider.LOCAL).isActive(true).build();

        group = Group.builder().id(UUID.randomUUID()).name("Trip to Goa")
                .createdBy(alice).inviteCode("TRIP1234").isActive(true).build();
    }

    // ── handleTyping() ────────────────────────────────────────────────────

    @Nested
    @DisplayName("handleTyping()")
    class HandleTyping {

        @Test
        @DisplayName("should set Redis key with 5s TTL when typing starts")
        void shouldSetKeyOnTypingStart() {
            arrangeMember();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            typingService.handleTyping("alice@test.com", group.getId(), true);

            String expectedKey = "typing:" + group.getId() + ":" + alice.getId();
            verify(valueOps).set(eq(expectedKey), eq("1"), eq(5L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should delete Redis key when typing stops")
        void shouldDeleteKeyOnTypingStop() {
            arrangeMember();

            typingService.handleTyping("alice@test.com", group.getId(), false);

            String expectedKey = "typing:" + group.getId() + ":" + alice.getId();
            verify(redisTemplate).delete(expectedKey);
        }

        @Test
        @DisplayName("should broadcast TypingEvent with typing=true to group topic")
        void shouldBroadcastTypingTrueEvent() {
            arrangeMember();
            when(redisTemplate.opsForValue()).thenReturn(valueOps);

            typingService.handleTyping("alice@test.com", group.getId(), true);

            ArgumentCaptor<TypingEvent> captor = ArgumentCaptor.forClass(TypingEvent.class);
            verify(messagingTemplate).convertAndSend(
                eq("/topic/groups/" + group.getId() + "/typing"),
                captor.capture()
            );

            TypingEvent event = captor.getValue();
            assertThat(event.getUserId()).isEqualTo(alice.getId());
            assertThat(event.getUsername()).isEqualTo("alice");
            assertThat(event.isTyping()).isTrue();
        }

        @Test
        @DisplayName("should broadcast TypingEvent with typing=false when stopped")
        void shouldBroadcastTypingFalseEvent() {
            arrangeMember();

            typingService.handleTyping("alice@test.com", group.getId(), false);

            ArgumentCaptor<TypingEvent> captor = ArgumentCaptor.forClass(TypingEvent.class);
            verify(messagingTemplate).convertAndSend(
                eq("/topic/groups/" + group.getId() + "/typing"),
                captor.capture()
            );

            assertThat(captor.getValue().isTyping()).isFalse();
        }

        @Test
        @DisplayName("should throw GroupNotFoundException when group does not exist")
        void shouldThrowForUnknownGroup() {
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> typingService.handleTyping("alice@test.com", UUID.randomUUID(), true))
                .isInstanceOf(GroupNotFoundException.class);

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("should throw NotMemberException for non-member")
        void shouldThrowForNonMember() {
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, alice)).thenReturn(false);

            assertThatThrownBy(() -> typingService.handleTyping("alice@test.com", group.getId(), true))
                .isInstanceOf(NotMemberException.class);

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("should throw GroupNotFoundException for inactive group")
        void shouldThrowForInactiveGroup() {
            Group inactive = Group.builder().id(UUID.randomUUID()).name("Old")
                    .createdBy(alice).inviteCode("OLD12345").isActive(false).build();
            when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
            when(groupRepository.findById(inactive.getId())).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> typingService.handleTyping("alice@test.com", inactive.getId(), true))
                .isInstanceOf(GroupNotFoundException.class);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void arrangeMember() {
        when(userRepository.findByEmailIgnoreCase("alice@test.com")).thenReturn(Optional.of(alice));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupAndUser(group, alice)).thenReturn(true);
    }
}
