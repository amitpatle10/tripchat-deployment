package com.tripchat.dto.response;

import com.tripchat.model.Group;
import com.tripchat.model.GroupMember;
import com.tripchat.model.enums.MemberRole;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * GroupResponse DTO — group representation returned to the client.
 *
 * inviteCode: exposed to all members (not just ADMIN).
 *   Any member can share the code to invite others — intentional.
 *   If leaked, ADMIN can regenerate it.
 *
 * myRole: the requesting user's role in this group (ADMIN or MEMBER).
 *   Client uses this to show/hide admin actions (delete, regenerate code).
 *
 * memberCount: current member count.
 *   Included so client can show "X/1000 members" without extra API call.
 *
 * Pattern: Static Factory Method — GroupResponse.from(group, membership, count)
 *   Mapping logic lives here, not scattered across service methods.
 */
@Getter
@Builder
public class GroupResponse {

    private UUID id;
    private String name;
    private String description;
    private String inviteCode;
    private int memberCount;
    private MemberRole myRole;
    private UUID createdBy;
    private Instant createdAt;
    private int unreadCount;

    public static GroupResponse from(Group group, GroupMember membership, int memberCount, int unreadCount) {
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .inviteCode(group.getInviteCode())
                .memberCount(memberCount)
                .myRole(membership.getRole())
                .createdBy(group.getCreatedBy().getId())
                .createdAt(group.getCreatedAt())
                .unreadCount(unreadCount)
                .build();
    }
}
