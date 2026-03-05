package com.tripchat.dto.response;

import com.tripchat.model.GroupMember;
import com.tripchat.model.enums.MemberRole;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * GroupMemberResponse — DTO for a single member within a group.
 *
 * Exposes only what the UI needs:
 *   userId, username, displayName — for rendering the avatar + name row.
 *   role — to show the ADMIN badge.
 *   joinedAt — to show "joined X ago" or sort by seniority.
 *
 * Pattern: Static Factory Method (from()) — same as MessageResponse and GroupResponse.
 * Keeps mapping logic in one place; callers never touch entity internals.
 */
@Getter
@Builder
public class GroupMemberResponse {

    private UUID userId;
    private String username;
    private String displayName;
    private MemberRole role;
    private Instant joinedAt;

    public static GroupMemberResponse from(GroupMember member) {
        return GroupMemberResponse.builder()
                .userId(member.getUser().getId())
                .username(member.getUser().getUsername())
                .displayName(member.getUser().getDisplayName())
                .role(member.getRole())
                .joinedAt(member.getJoinedAt())
                .build();
    }
}
