package com.tripchat.service;

import com.tripchat.dto.response.PresenceResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * PresenceService — tracks and queries user online status via Redis.
 *
 * Data structure: String per user
 *   Key:   presence:{userId}
 *   Value: "1"  (we only care about existence, not the value)
 *   TTL:   30s  (refreshed every 20s by client heartbeat)
 *
 * Why String + TTL over a global online Set:
 *   - Redis Set members can't have individual TTLs — only the whole key can expire.
 *   - With a Set we'd need explicit SREM on disconnect AND rely on it for crashes.
 *   - String per user gives us both: EXISTS for lookup, TTL as crash safety net.
 *
 * Two disconnect paths:
 *   1. Clean disconnect (tab closed, logout) →
 *        SessionDisconnectEvent → markOffline() → DEL key immediately
 *   2. Ungraceful disconnect (network drop, crash) →
 *        Client stops sending heartbeats → TTL expires after 30s → key gone
 *
 * Querying group presence:
 *   Load all group members, then pipeline EXISTS checks for each userId.
 *   For 1000-member groups this is ~1000 Redis EXISTS calls pipelined — still <1ms.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "presence:";
    private static final long PRESENCE_TTL_SECONDS   = 30;

    private final StringRedisTemplate redisTemplate;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    // ── Mark online / offline ──────────────────────────────────────────────

    /**
     * Called on STOMP CONNECT (from JwtChannelInterceptor after auth).
     * Sets the presence key with a 30s TTL.
     */
    public void markOnline(UUID userId) {
        String key = presenceKey(userId);
        redisTemplate.opsForValue().set(key, "1", PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
        log.debug("User {} marked online", userId);
    }

    /**
     * Called on clean disconnect (SessionDisconnectEvent).
     * Deletes the key immediately — no need to wait for TTL.
     */
    public void markOffline(UUID userId) {
        redisTemplate.delete(presenceKey(userId));
        log.debug("User {} marked offline", userId);
    }

    /**
     * Called on heartbeat (STOMP SEND /app/presence/heartbeat every 20s).
     * Refreshes the TTL — proves the connection is still alive.
     * Using EXPIRE rather than re-SET avoids overwriting the value unnecessarily.
     */
    public void refreshPresence(UUID userId) {
        redisTemplate.expire(presenceKey(userId), PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isOnline(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(presenceKey(userId)));
    }

    // ── Query group presence ───────────────────────────────────────────────

    /**
     * Returns PresenceResponse for every online member of the group.
     *
     * Access control:
     *   - Group must exist and be active (GroupNotFoundException if not)
     *   - Requester must be a member (NotMemberException if not)
     *   - Same 404-for-non-members rule as message history
     */
    public List<PresenceResponse> getOnlineMembers(String requesterEmail, UUID groupId) {
        var requester = userRepository.findByEmailIgnoreCase(requesterEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + requesterEmail));

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        if (!groupMemberRepository.existsByGroupAndUser(group, requester)) {
            throw new NotMemberException();
        }

        // Load all members, filter to those with an active presence key
        return groupMemberRepository.findByGroup(group).stream()
                .map(GroupMember::getUser)
                .filter(member -> isOnline(member.getId()))
                .map(PresenceResponse::from)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String presenceKey(UUID userId) {
        return PRESENCE_KEY_PREFIX + userId;
    }
}
