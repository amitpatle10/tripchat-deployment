package com.tripchat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnreadService")
class UnreadServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;

    @InjectMocks private UnreadService unreadService;

    private UUID userId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId  = UUID.randomUUID();
        groupId = UUID.randomUUID();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
    }

    @Nested
    @DisplayName("increment()")
    class Increment {

        @Test
        @DisplayName("should call HINCRBY with delta 1")
        void shouldCallHincrby() {
            unreadService.increment(userId, groupId);

            verify(hashOps).increment("unread:" + userId, groupId.toString(), 1L);
        }
    }

    @Nested
    @DisplayName("reset()")
    class Reset {

        @Test
        @DisplayName("should delete the hash field for the group")
        void shouldDeleteHashField() {
            unreadService.reset(userId, groupId);

            verify(hashOps).delete("unread:" + userId, groupId.toString());
        }
    }

    @Nested
    @DisplayName("getCount()")
    class GetCount {

        @Test
        @DisplayName("should return parsed count when key exists")
        void shouldReturnCount() {
            when(hashOps.get("unread:" + userId, groupId.toString())).thenReturn("5");

            assertThat(unreadService.getCount(userId, groupId)).isEqualTo(5);
        }

        @Test
        @DisplayName("should return 0 when key does not exist")
        void shouldReturnZeroWhenMissing() {
            when(hashOps.get("unread:" + userId, groupId.toString())).thenReturn(null);

            assertThat(unreadService.getCount(userId, groupId)).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("getAllCounts()")
    class GetAllCounts {

        @Test
        @DisplayName("should return map of groupId to count")
        void shouldReturnAllCounts() {
            UUID group1 = UUID.randomUUID();
            UUID group2 = UUID.randomUUID();
            when(hashOps.entries("unread:" + userId)).thenReturn(Map.of(
                group1.toString(), "3",
                group2.toString(), "7"
            ));

            Map<UUID, Integer> result = unreadService.getAllCounts(userId);

            assertThat(result).containsEntry(group1, 3).containsEntry(group2, 7);
        }

        @Test
        @DisplayName("should return empty map when user has no unread counts")
        void shouldReturnEmptyMapWhenNoData() {
            when(hashOps.entries("unread:" + userId)).thenReturn(Map.of());

            assertThat(unreadService.getAllCounts(userId)).isEmpty();
        }
    }
}
