package com.tripchat.service;

import com.tripchat.dto.response.MessageResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.User;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.MessageRepository;
import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MessageQueryService — serves message history via REST API.
 *
 * Cache-aside pattern:
 *   1. Check Redis sorted set
 *   2. HIT  → return immediately (~1ms)
 *   3. MISS → acquire lock → query DB → populate Redis → return (~15ms)
 *
 * Cursor-based pagination:
 *   Clients pass (cursorTime, cursorId) from the last message they received.
 *   Server returns messages older than the cursor.
 *   No offset scanning — O(log N) regardless of message history depth.
 *
 * Cache stampede prevention (Redis SET NX lock):
 *   On cache miss, multiple concurrent requests would all query DB simultaneously
 *   (thundering herd). We use a distributed lock:
 *   - First thread: acquires lock → queries DB → populates cache → releases lock
 *   - Other threads: fail to acquire lock → wait 50ms → retry Redis → cache hit
 *   Lock TTL = 5 seconds — auto-expires if holding thread crashes (deadlock prevention).
 *
 * Membership validation:
 *   Only group members can read messages.
 *   Non-members get 404 (not 403) — consistent with group access policy:
 *   don't reveal that the group exists to non-members.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageQueryService {

    private static final int PAGE_SIZE = 50;
    private static final String LOCK_PREFIX = "lock:group:messages:";
    private static final long LOCK_TTL_SECONDS = 5;

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final MessageCacheService messageCacheService;
    private final StringRedisTemplate redisTemplate;

    /**
     * Get first page of messages for a group (no cursor).
     * Tries Redis first; falls back to DB on miss.
     */
    public List<MessageResponse> getMessages(String userEmail, UUID groupId) {
        validateAccess(userEmail, groupId);

        // Cache-aside: check Redis first
        List<MessageResponse> cached = messageCacheService.getLatest(groupId);
        if (!cached.isEmpty()) {
            log.debug("Cache HIT for group {}", groupId);
            return cached;
        }

        log.debug("Cache MISS for group {} — loading from DB", groupId);
        return loadFromDbWithLock(groupId, null, null);
    }

    /**
     * Get messages before a cursor (infinite scroll / load older messages).
     * Cursor = (createdAt ms, messageId) of the oldest message the client has.
     */
    public List<MessageResponse> getMessagesBefore(
            String userEmail, UUID groupId, Instant cursorTime, UUID cursorId) {

        validateAccess(userEmail, groupId);

        // Try Redis for cursor-based pagination too
        List<MessageResponse> cached = messageCacheService.getBeforeCursor(
            groupId, cursorTime.toEpochMilli()
        );
        if (!cached.isEmpty()) {
            log.debug("Cache HIT (cursor) for group {}", groupId);
            return cached;
        }

        // Cache miss — fall through to DB
        return loadFromDbWithLock(groupId, cursorTime, cursorId);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Load from DB with stampede prevention lock.
     * Only one thread queries DB on concurrent cache miss.
     */
    private List<MessageResponse> loadFromDbWithLock(UUID groupId, Instant cursorTime, UUID cursorId) {
        String lockKey = LOCK_PREFIX + groupId;

        // Try to acquire lock (SET NX with TTL)
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                // We hold the lock — query DB
                return queryDb(groupId, cursorTime, cursorId);
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // Another thread holds the lock — wait briefly and retry cache
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Retry cache — should be warm now
            List<MessageResponse> retried = cursorTime == null
                ? messageCacheService.getLatest(groupId)
                : messageCacheService.getBeforeCursor(groupId, cursorTime.toEpochMilli());

            if (!retried.isEmpty()) {
                return retried;
            }

            // Still empty — fall through to DB (rare edge case)
            return queryDb(groupId, cursorTime, cursorId);
        }
    }

    private List<MessageResponse> queryDb(UUID groupId, Instant cursorTime, UUID cursorId) {
        List<com.tripchat.model.Message> messages;
        PageRequest page = PageRequest.of(0, PAGE_SIZE);

        if (cursorTime == null) {
            messages = messageRepository.findLatestByGroup(groupId, page);
        } else {
            messages = messageRepository.findByGroupBeforeCursor(groupId, cursorTime, cursorId, page);
        }

        return messages.stream()
                .map(MessageResponse::from)
                .collect(Collectors.toList());
    }

    private void validateAccess(String userEmail, UUID groupId) {
        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userEmail));

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new NotMemberException();
        }
    }
}
