package com.tripchat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripchat.dto.response.MessageResponse;
import com.tripchat.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MessageCacheService — Redis sorted set cache for recent messages.
 *
 * Data structure: Sorted Set
 *   Key:    group:messages:{groupId}
 *   Score:  created_at epoch milliseconds (chronological ordering)
 *   Member: serialized MessageResponse JSON
 *
 *   ZREVRANGE returns messages newest-first (DESC score order).
 *   ZREVRANGEBYSCORE enables cursor-based pagination by timestamp.
 *   O(log N) insert, O(log N + K) read — efficient at any scale.
 *
 * Cache patterns in use:
 *   Write-through: Kafka consumer calls cacheMessage() after DB persist.
 *                  Cache is always up to date with DB.
 *   Cache-aside:   MessageQueryService checks Redis first; DB on miss.
 *
 * TTL (24 hours):
 *   Inactive group caches expire automatically.
 *   Active group TTL is refreshed on every read (keeps hot groups warm).
 *
 * Max entries (50):
 *   After every ZADD, trim to 50 entries using ZREMRANGEBYRANK.
 *   Removes the oldest entry when size exceeds 50.
 *   History beyond 50 is served from PostgreSQL.
 *
 * Stampede prevention (SET NX lock) is handled in MessageQueryService
 * at the cache-miss read path — not here (write path never stampedes).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageCacheService {

    private static final String KEY_PREFIX = "group:messages:";
    private static final int MAX_CACHE_SIZE = 50;
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Write-through — called by Kafka consumer after DB persist.
     * Adds message to sorted set, trims to 50, refreshes TTL.
     */
    public void cacheMessage(Message message) {
        String key = buildKey(message.getGroup().getId());
        MessageResponse response = MessageResponse.from(message);

        try {
            String json = objectMapper.writeValueAsString(response);
            double score = message.getCreatedAt().toEpochMilli();

            // ZADD key score member
            redisTemplate.opsForZSet().add(key, json, score);

            // Trim to max 50 — remove oldest entries (lowest scores)
            // ZREMRANGEBYRANK key 0 -(MAX+1) removes entries beyond the limit
            redisTemplate.opsForZSet().removeRange(key, 0, -(MAX_CACHE_SIZE + 1));

            // Refresh TTL on every write — active groups stay warm
            redisTemplate.expire(key, TTL);

            log.debug("Cached message: clientId={}, group={}", message.getClientId(), message.getGroup().getId());

        } catch (JsonProcessingException e) {
            // Cache failure is non-fatal — message is already in DB
            log.error("Failed to cache message in Redis: clientId={}", message.getClientId(), e);
        }
    }

    /**
     * Cache-aside read — get last 50 messages for a group.
     * Returns empty list on cache miss (caller falls back to DB).
     */
    public List<MessageResponse> getLatest(UUID groupId) {
        String key = buildKey(groupId);

        // ZREVRANGE — newest first (highest score = most recent)
        Set<String> members = redisTemplate.opsForZSet().reverseRange(key, 0, MAX_CACHE_SIZE - 1);

        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }

        // Refresh TTL on read — active groups stay warm indefinitely
        redisTemplate.expire(key, TTL);

        return deserialize(members);
    }

    /**
     * Cache-aside read — cursor-based pagination.
     * Returns messages with score (created_at ms) less than cursorMs.
     */
    public List<MessageResponse> getBeforeCursor(UUID groupId, long cursorMs) {
        String key = buildKey(groupId);

        // ZREVRANGEBYSCORE key (cursorMs-1) -inf LIMIT 0 50
        // cursorMs - 1 excludes the cursor message itself
        Set<String> members = redisTemplate.opsForZSet()
                .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, cursorMs - 1, 0, MAX_CACHE_SIZE);

        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }

        redisTemplate.expire(key, TTL);
        return deserialize(members);
    }

    /**
     * Check if a group's cache key exists.
     * Used to determine cache-hit vs cache-miss before acquiring DB lock.
     */
    public boolean hasCache(UUID groupId) {
        Boolean exists = redisTemplate.hasKey(buildKey(groupId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Evict the entire sorted set for a group.
     * Called when a message is deleted — simpler and safer than scanning
     * members to remove one entry (members are JSON strings, not IDs).
     * The cache is rebuilt on the next read from DB.
     */
    public void evictGroup(UUID groupId) {
        redisTemplate.delete(buildKey(groupId));
        log.debug("Evicted message cache for group {}", groupId);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String buildKey(UUID groupId) {
        return KEY_PREFIX + groupId;
    }

    private List<MessageResponse> deserialize(Set<String> members) {
        return members.stream()
                .map(json -> {
                    try {
                        return objectMapper.readValue(json, MessageResponse.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize cached message", e);
                        return null;
                    }
                })
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }
}
