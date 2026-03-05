package com.tripchat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * UnreadService — tracks per-user per-group unread message counts in Redis.
 *
 * Data structure: Redis Hash
 *   Key:    unread:{userId}
 *   Fields: { groupId → count }
 *
 * Why Hash over individual String keys?
 *   With String keys, loading unread counts for a user with 10 groups
 *   = 10 Redis round-trips. With a Hash, HGETALL = 1 round-trip for all groups.
 *
 * Why HINCRBY over get-then-set?
 *   HINCRBY is atomic. If two messages arrive simultaneously for the same user+group,
 *   both increments are applied correctly without a race condition:
 *     Thread A: HINCRBY → count 0→1
 *     Thread B: HINCRBY → count 1→2   ✓
 *   A get-then-set approach would produce count=1 for both threads (lost update).
 *
 * No TTL:
 *   Unread counts must survive indefinitely — if a user doesn't open a group for
 *   days, their count stays. We reset explicitly on mark-as-read, not by expiry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnreadService {

    private static final String UNREAD_KEY_PREFIX = "unread:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Increments the unread count for a user in a specific group by 1.
     * Called by the Kafka consumer after each message is persisted.
     * HINCRBY creates the hash / field if it doesn't exist yet.
     */
    public void increment(UUID userId, UUID groupId) {
        redisTemplate.opsForHash().increment(
            unreadKey(userId), groupId.toString(), 1L
        );
    }

    /**
     * Resets the unread count for a user in a specific group to 0.
     * Called when the user opens the group (mark as read).
     * HDEL removes the field entirely — equivalent to count = 0.
     */
    public void reset(UUID userId, UUID groupId) {
        redisTemplate.opsForHash().delete(unreadKey(userId), groupId.toString());
    }

    /**
     * Returns the unread count for a single user+group pair.
     * Returns 0 if no unread messages or key doesn't exist.
     */
    public int getCount(UUID userId, UUID groupId) {
        Object value = redisTemplate.opsForHash().get(unreadKey(userId), groupId.toString());
        return value == null ? 0 : Integer.parseInt(value.toString());
    }

    /**
     * Returns all unread counts for a user as a map of groupId → count.
     * One Redis call (HGETALL) regardless of how many groups the user is in.
     * Used by GroupService.getMyGroups() to attach counts to each group.
     */
    public Map<UUID, Integer> getAllCounts(UUID userId) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(unreadKey(userId));
        return raw.entrySet().stream()
                .collect(Collectors.toMap(
                    e -> UUID.fromString(e.getKey().toString()),
                    e -> Integer.parseInt(e.getValue().toString())
                ));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private String unreadKey(UUID userId) {
        return UNREAD_KEY_PREFIX + userId;
    }
}
