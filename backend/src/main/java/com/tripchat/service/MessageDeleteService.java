package com.tripchat.service;

import com.tripchat.dto.response.MessageResponse;
import com.tripchat.exception.GroupNotFoundException;
import com.tripchat.exception.MessageNotFoundException;
import com.tripchat.exception.NotMemberException;
import com.tripchat.exception.UnauthorizedGroupActionException;
import com.tripchat.model.Group;
import com.tripchat.model.Message;
import com.tripchat.model.User;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.MessageRepository;
import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * MessageDeleteService — handles soft deletion of a single chat message.
 *
 * Authorization model:
 *   Only the original sender can delete their own message.
 *   A null sender means the user account was deleted — nobody can delete
 *   those tombstone messages (they are already "dead").
 *
 * Soft delete (deletedAt):
 *   We never physically remove messages from the DB. Setting deletedAt
 *   preserves conversation context (no gaps) while hiding the content.
 *   The UI renders "This message was deleted" in place of the bubble.
 *
 * Cache eviction:
 *   Redis sorted set stores JSON-serialized MessageResponse objects as members.
 *   Finding and removing a single entry requires scanning all members and
 *   matching by id — O(N) and fragile. Evicting the entire group key is O(1)
 *   and correct. The cache is rebuilt on the next read. Deletes are rare
 *   so the cold-path rebuild cost is acceptable.
 *
 * STOMP broadcast:
 *   After soft delete, we broadcast the updated MessageResponse (deleted=true)
 *   to all online group members. Their addMessage handlers replace the existing
 *   entry in the TanStack Query cache by clientId, showing the tombstone
 *   immediately without requiring a page reload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageDeleteService {

    private final MessageRepository messageRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final MessageCacheService messageCacheService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void deleteMessage(String userEmail, UUID groupId, UUID messageId) {

        User user = userRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userEmail));

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        // Non-members get 404, not 403 — consistent with group access policy
        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new NotMemberException();
        }

        // Scoped lookup — message must belong to this group (prevents cross-group deletion)
        Message message = messageRepository.findByIdAndGroup_Id(messageId, groupId)
                .orElseThrow(() -> new MessageNotFoundException(messageId));

        // Only the sender can delete their own message
        if (message.getSender() == null || !message.getSender().getId().equals(user.getId())) {
            throw new UnauthorizedGroupActionException("You can only delete your own messages");
        }

        // Soft delete — set deletedAt, message stays in DB
        message.setDeletedAt(Instant.now());
        messageRepository.save(message);

        // Evict Redis cache — the cached JSON for this group is now stale
        messageCacheService.evictGroup(groupId);

        // Broadcast deletion to all online group members via STOMP
        // addMessage on the client will replace the old entry (matched by clientId)
        // with deleted=true, showing the "This message was deleted" tombstone
        String destination = "/topic/groups/" + groupId;
        messagingTemplate.convertAndSend(destination, MessageResponse.from(message));

        log.debug("Message soft-deleted: messageId={}, group={}, by={}", messageId, groupId, userEmail);
    }
}
