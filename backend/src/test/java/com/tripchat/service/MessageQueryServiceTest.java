package com.tripchat.service;

import com.tripchat.dto.response.MessageResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.Message;
import com.tripchat.model.User;
import com.tripchat.model.enums.AuthProvider;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.MessageRepository;
import com.tripchat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MessageQueryServiceTest — unit tests for the REST message history read path.
 *
 * Cache-aside logic under test:
 *   - Cache HIT  → return from Redis, no DB query
 *   - Cache MISS → acquire lock → query DB → return results
 *   - Non-member → NotMemberException (404 security — don't reveal group existence)
 *   - Group not found → GroupNotFoundException
 *
 * Stampede prevention (SET NX lock) is tested for the cache-miss path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageQueryService")
class MessageQueryServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageCacheService messageCacheService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks
    private MessageQueryService messageQueryService;

    private User user;
    private Group group;
    private Message message;
    private MessageResponse cachedResponse;

    @BeforeEach
    void setUp() {
        user = User.builder()
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
                .createdBy(user)
                .inviteCode("TRIP1234")
                .isActive(true)
                .build();

        message = Message.builder()
                .id(UUID.randomUUID())
                .group(group)
                .sender(user)
                .content("Hello!")
                .clientId(UUID.randomUUID())
                .build();

        // Use reflection to set createdAt (managed by JPA auditing, no setter)
        setField(message, "createdAt", Instant.now());

        cachedResponse = MessageResponse.from(message);
    }

    // ── getMessages() ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMessages()")
    class GetMessages {

        @Test
        @DisplayName("should return cached messages on cache hit without querying DB")
        void shouldReturnCachedOnHit() {
            arrangeValidMember();
            when(messageCacheService.getLatest(group.getId())).thenReturn(List.of(cachedResponse));

            List<MessageResponse> result = messageQueryService.getMessages("amit@test.com", group.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("Hello!");
            verify(messageRepository, never()).findLatestByGroup(any(), any());
        }

        @Test
        @DisplayName("should query DB on cache miss and return results")
        void shouldQueryDbOnCacheMiss() {
            arrangeValidMember();
            when(messageCacheService.getLatest(group.getId())).thenReturn(List.of());
            arrangeRedisLockAcquired();
            when(messageRepository.findLatestByGroup(eq(group.getId()), any(Pageable.class)))
                .thenReturn(List.of(message));

            List<MessageResponse> result = messageQueryService.getMessages("amit@test.com", group.getId());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("Hello!");
            verify(messageRepository).findLatestByGroup(eq(group.getId()), any(Pageable.class));
        }

        @Test
        @DisplayName("should return empty list when group has no messages")
        void shouldReturnEmptyListWhenNoMessages() {
            arrangeValidMember();
            when(messageCacheService.getLatest(group.getId())).thenReturn(List.of());
            arrangeRedisLockAcquired();
            when(messageRepository.findLatestByGroup(any(), any())).thenReturn(List.of());

            List<MessageResponse> result = messageQueryService.getMessages("amit@test.com", group.getId());

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw GroupNotFoundException when group does not exist")
        void shouldThrowWhenGroupNotFound() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(user));
            when(groupRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> messageQueryService.getMessages("amit@test.com", UUID.randomUUID()))
                    .isInstanceOf(GroupNotFoundException.class);

            verify(messageCacheService, never()).getLatest(any());
        }

        @Test
        @DisplayName("should throw NotMemberException when user is not a group member")
        void shouldThrowWhenNotMember() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(user));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, user)).thenReturn(false);

            assertThatThrownBy(() -> messageQueryService.getMessages("amit@test.com", group.getId()))
                    .isInstanceOf(NotMemberException.class);

            verify(messageCacheService, never()).getLatest(any());
        }

        @Test
        @DisplayName("should throw GroupNotFoundException for inactive group")
        void shouldThrowWhenGroupInactive() {
            Group inactiveGroup = Group.builder()
                    .id(UUID.randomUUID()).name("Old").createdBy(user)
                    .inviteCode("OLD12345").isActive(false).build();

            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(user));
            when(groupRepository.findById(inactiveGroup.getId())).thenReturn(Optional.of(inactiveGroup));

            assertThatThrownBy(() -> messageQueryService.getMessages("amit@test.com", inactiveGroup.getId()))
                    .isInstanceOf(GroupNotFoundException.class);
        }

        @Test
        @DisplayName("should release Redis lock after DB query completes")
        void shouldReleaseLockAfterDbQuery() {
            arrangeValidMember();
            when(messageCacheService.getLatest(group.getId())).thenReturn(List.of());
            arrangeRedisLockAcquired();
            when(messageRepository.findLatestByGroup(any(), any())).thenReturn(List.of(message));

            messageQueryService.getMessages("amit@test.com", group.getId());

            verify(redisTemplate).delete(contains("lock:group:messages:"));
        }
    }

    // ── getMessagesBefore() ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getMessagesBefore()")
    class GetMessagesBefore {

        private Instant cursorTime;
        private UUID cursorId;

        @BeforeEach
        void setUpCursor() {
            cursorTime = Instant.now().minusSeconds(60);
            cursorId = UUID.randomUUID();
        }

        @Test
        @DisplayName("should return cached messages for cursor on cache hit")
        void shouldReturnCachedOnCursorHit() {
            arrangeValidMember();
            when(messageCacheService.getBeforeCursor(group.getId(), cursorTime.toEpochMilli()))
                .thenReturn(List.of(cachedResponse));

            List<MessageResponse> result = messageQueryService.getMessagesBefore(
                "amit@test.com", group.getId(), cursorTime, cursorId
            );

            assertThat(result).hasSize(1);
            verify(messageRepository, never()).findByGroupBeforeCursor(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should query DB with cursor on cache miss")
        void shouldQueryDbWithCursorOnMiss() {
            arrangeValidMember();
            when(messageCacheService.getBeforeCursor(any(), anyLong())).thenReturn(List.of());
            arrangeRedisLockAcquired();
            when(messageRepository.findByGroupBeforeCursor(
                eq(group.getId()), eq(cursorTime), eq(cursorId), any(Pageable.class)))
                .thenReturn(List.of(message));

            List<MessageResponse> result = messageQueryService.getMessagesBefore(
                "amit@test.com", group.getId(), cursorTime, cursorId
            );

            assertThat(result).hasSize(1);
            verify(messageRepository).findByGroupBeforeCursor(
                eq(group.getId()), eq(cursorTime), eq(cursorId), any(Pageable.class)
            );
        }

        @Test
        @DisplayName("should return empty list when no older messages exist")
        void shouldReturnEmptyListAtEndOfHistory() {
            arrangeValidMember();
            when(messageCacheService.getBeforeCursor(any(), anyLong())).thenReturn(List.of());
            arrangeRedisLockAcquired();
            when(messageRepository.findByGroupBeforeCursor(any(), any(), any(), any()))
                .thenReturn(List.of());

            List<MessageResponse> result = messageQueryService.getMessagesBefore(
                "amit@test.com", group.getId(), cursorTime, cursorId
            );

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should throw NotMemberException when non-member queries cursor page")
        void shouldThrowWhenNotMemberWithCursor() {
            when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(user));
            when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
            when(groupMemberRepository.existsByGroupAndUser(group, user)).thenReturn(false);

            assertThatThrownBy(() -> messageQueryService.getMessagesBefore(
                "amit@test.com", group.getId(), cursorTime, cursorId))
                    .isInstanceOf(NotMemberException.class);
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void arrangeValidMember() {
        when(userRepository.findByEmailIgnoreCase("amit@test.com")).thenReturn(Optional.of(user));
        when(groupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(groupMemberRepository.existsByGroupAndUser(group, user)).thenReturn(true);
    }

    private void arrangeRedisLockAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
            .thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);
    }

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
