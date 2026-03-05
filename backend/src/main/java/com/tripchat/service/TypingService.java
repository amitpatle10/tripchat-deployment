package com.tripchat.service;

import com.tripchat.dto.response.TypingEvent;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.model.Group;
import com.tripchat.model.User;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * TypingService — manages typing state and broadcasts typing events.
 *
 * Redis key: typing:{groupId}:{userId}
 *   Value: "1"
 *   TTL:   5s (dead man's switch — auto-clears if client crashes)
 *
 * Client contract:
 *   - Send typing=true when user starts typing
 *   - Resend typing=true every 3s while still typing (refreshes TTL)
 *   - Send typing=false when user stops / sends the message
 *
 * Why Redis for typing state?
 *   We could just relay the frame without storing state — simpler.
 *   But then a crashed client leaves the indicator on forever.
 *   The 5s TTL is the crash recovery mechanism. The tradeoff is
 *   a small Redis write per keypress (effectively ~1 write / 3s per active typer).
 *   At 1000 DAUs with maybe 10% typing at once: ~33 writes/s — negligible.
 *
 * Broadcast destination: /topic/groups/{groupId}/typing
 *   All group members subscribed to this topic receive the event.
 *   Separate from the message topic so clients can handle them differently
 *   (e.g., don't persist typing events, don't show in history).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TypingService {

    private static final String TYPING_KEY_PREFIX  = "typing:";
    private static final long   TYPING_TTL_SECONDS = 5;

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    /**
     * Handles a typing event from a group member.
     *
     * On typing=true:  SET key with 5s TTL (or refresh if already set)
     * On typing=false: DEL key immediately
     * Either way: broadcast TypingEvent to all group subscribers.
     */
    public void handleTyping(String senderEmail, UUID groupId, boolean typing) {
        User sender = userRepository.findByEmailIgnoreCase(senderEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + senderEmail));

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        if (!groupMemberRepository.existsByGroupAndUser(group, sender)) {
            throw new NotMemberException();
        }

        String key = typingKey(groupId, sender.getId());

        if (typing) {
            // SET with TTL — refreshes if already present (client resending every 3s)
            redisTemplate.opsForValue().set(key, "1", TYPING_TTL_SECONDS, TimeUnit.SECONDS);
        } else {
            redisTemplate.delete(key);
        }

        // Broadcast to all group members on the typing sub-topic
        TypingEvent event = new TypingEvent(
                sender.getId(),
                sender.getUsername(),
                sender.getDisplayName(),
                typing
        );

        messagingTemplate.convertAndSend("/topic/groups/" + groupId + "/typing", event);

        log.debug("Typing event: user={}, group={}, typing={}", sender.getUsername(), groupId, typing);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String typingKey(UUID groupId, UUID userId) {
        return TYPING_KEY_PREFIX + groupId + ":" + userId;
    }
}
