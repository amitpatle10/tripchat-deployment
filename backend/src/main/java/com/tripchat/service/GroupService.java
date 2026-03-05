package com.tripchat.service;

import com.tripchat.dto.request.CreateGroupRequest;
import com.tripchat.dto.request.JoinGroupRequest;
import com.tripchat.dto.response.GroupMemberResponse;
import com.tripchat.dto.response.GroupResponse;
import com.tripchat.exception.*;
import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.model.User;
import com.tripchat.model.enums.MemberRole;
import com.tripchat.repository.GroupMemberRepository;
import com.tripchat.repository.GroupRepository;
import com.tripchat.repository.UserRepository;
import com.tripchat.util.InviteCodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GroupService — business logic for group lifecycle operations.
 *
 * @Transactional on write methods:
 *   createGroup: saves Group + GroupMember atomically.
 *     If membership save fails, group save is rolled back. No orphan groups.
 *   joinGroup: membership check + save must be atomic.
 *     Without transaction, two concurrent joins could both pass the duplicate
 *     check and both insert — DB unique constraint is the final guard.
 *   leaveGroup: single delete, but transactional for consistency.
 *
 * N+1 awareness in getMyGroups:
 *   GroupMemberRepository.findByUser uses JOIN FETCH to load groups in one query.
 *   countByGroup is still called per group — acceptable at Phase 1 scale.
 *   Future optimisation: cache member counts in Redis.
 *
 * Current user loading:
 *   Controller passes email from Spring Security's UserDetails.
 *   Service loads the full User entity — needed to set FK relationships.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupService {

    private static final int MAX_MEMBERS = 1000;

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final UnreadService unreadService;

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public GroupResponse createGroup(String userEmail, CreateGroupRequest request) {
        User creator = loadUser(userEmail);

        Group group = Group.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(creator)
                .inviteCode(generateUniqueCode())
                .build();

        Group savedGroup = groupRepository.save(group);

        // Creator automatically becomes ADMIN — atomic with group save
        GroupMember adminMembership = GroupMember.builder()
                .group(savedGroup)
                .user(creator)
                .role(MemberRole.ADMIN)
                .build();

        groupMemberRepository.save(adminMembership);
        log.info("Group created: '{}' ({}) by {}", savedGroup.getName(), savedGroup.getId(), userEmail);

        return GroupResponse.from(savedGroup, adminMembership, 1, 0);
    }

    // ── Join ──────────────────────────────────────────────────────────────────

    @Transactional
    public GroupResponse joinGroup(String userEmail, JoinGroupRequest request) {
        User user = loadUser(userEmail);

        // 404 for wrong code — don't confirm group exists (security)
        Group group = groupRepository.findByInviteCode(request.getInviteCode())
                .filter(Group::isActive)
                .orElseThrow(InvalidInviteCodeException::new);

        if (groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new AlreadyMemberException();
        }

        int currentCount = groupMemberRepository.countByGroup(group);
        if (currentCount >= MAX_MEMBERS) {
            throw new GroupFullException();
        }

        GroupMember membership = GroupMember.builder()
                .group(group)
                .user(user)
                .role(MemberRole.MEMBER)
                .build();

        groupMemberRepository.save(membership);
        log.info("User {} joined group '{}'", userEmail, group.getName());

        return GroupResponse.from(group, membership, currentCount + 1, 0);
    }

    // ── Leave ─────────────────────────────────────────────────────────────────

    @Transactional
    public void leaveGroup(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        // 404 for non-members — don't confirm group exists to non-members
        GroupMember membership = groupMemberRepository.findByGroupAndUser(group, user)
                .orElseThrow(NotMemberException::new);

        if (membership.getRole() == MemberRole.ADMIN) {
            throw new AdminCannotLeaveException();
        }

        groupMemberRepository.delete(membership);
        log.info("User {} left group '{}'", userEmail, group.getName());
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<GroupResponse> getMyGroups(String userEmail) {
        User user = loadUser(userEmail);

        // One HGETALL call for all unread counts — avoids N Redis round-trips
        var unreadCounts = unreadService.getAllCounts(user.getId());

        return groupMemberRepository.findByUser(user).stream()
                .map(membership -> GroupResponse.from(
                        membership.getGroup(),
                        membership,
                        groupMemberRepository.countByGroup(membership.getGroup()),
                        unreadCounts.getOrDefault(membership.getGroup().getId(), 0)
                ))
                .collect(Collectors.toList());
    }

    public GroupResponse getGroupById(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        GroupMember membership = groupMemberRepository.findByGroupAndUser(group, user)
                .orElseThrow(NotMemberException::new);

        return GroupResponse.from(
                group, membership,
                groupMemberRepository.countByGroup(group),
                unreadService.getCount(user.getId(), groupId)
        );
    }

    // ── Mark as Read ──────────────────────────────────────────────────────────

    public void markAsRead(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new NotMemberException();
        }

        unreadService.reset(user.getId(), groupId);
        log.debug("Marked as read: user={}, group={}", userEmail, groupId);
    }

    // ── Members ───────────────────────────────────────────────────────────────

    /**
     * Returns all members of a group. Any group member may call this.
     * Non-members receive 404 — consistent with the group access policy.
     * Members are returned sorted: ADMINs first, then by joinedAt ascending.
     */
    public List<GroupMemberResponse> getGroupMembers(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new NotMemberException();
        }

        return groupMemberRepository.findByGroup(group).stream()
                .sorted((a, b) -> {
                    // ADMINs first, then oldest members first
                    if (a.getRole() != b.getRole()) {
                        return a.getRole().name().compareTo(b.getRole().name()); // ADMIN < MEMBER
                    }
                    return a.getJoinedAt().compareTo(b.getJoinedAt());
                })
                .map(GroupMemberResponse::from)
                .collect(Collectors.toList());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Soft-deletes a group. Only the group ADMIN can perform this action.
     * Sets isActive = false — preserves all messages and member records for audit.
     * Non-members receive 404; members with MEMBER role receive 403.
     */
    @Transactional
    public void deleteGroup(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        GroupMember membership = groupMemberRepository.findByGroupAndUser(group, user)
                .orElseThrow(NotMemberException::new);

        if (membership.getRole() != MemberRole.ADMIN) {
            throw new UnauthorizedGroupActionException("delete group");
        }

        group.setActive(false);
        groupRepository.save(group);
        log.info("Group soft-deleted: '{}' ({}) by {}", group.getName(), groupId, userEmail);
    }

    // ── Regenerate Invite Code ────────────────────────────────────────────────

    @Transactional
    public GroupResponse regenerateInviteCode(String userEmail, UUID groupId) {
        User user = loadUser(userEmail);

        Group group = groupRepository.findById(groupId)
                .filter(Group::isActive)
                .orElseThrow(() -> new GroupNotFoundException(groupId));

        GroupMember membership = groupMemberRepository.findByGroupAndUser(group, user)
                .orElseThrow(NotMemberException::new);

        if (membership.getRole() != MemberRole.ADMIN) {
            throw new UnauthorizedGroupActionException("regenerate invite code");
        }

        group.setInviteCode(generateUniqueCode());
        Group updated = groupRepository.save(group);
        log.info("Invite code regenerated for group '{}' by {}", group.getName(), userEmail);

        return GroupResponse.from(
                updated, membership,
                groupMemberRepository.countByGroup(updated),
                unreadService.getCount(user.getId(), groupId)
        );
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private User loadUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    /**
     * Generate a unique invite code — retry on collision (extremely rare).
     * 32^8 = 1 trillion combinations. At 1000 groups, collision probability ≈ 0.
     * Loop is a safety net, not an expected path.
     */
    private String generateUniqueCode() {
        String code;
        do {
            code = inviteCodeGenerator.generate();
        } while (groupRepository.existsByInviteCode(code));
        return code;
    }
}
