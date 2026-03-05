package com.tripchat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JoinGroupRequest DTO — input for POST /api/v1/groups/join
 *
 * inviteCode: the 8-char code shared by a group member.
 * No group ID needed — the code uniquely identifies the group.
 * This matches how Discord/Telegram invite links work.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class JoinGroupRequest {

    @NotBlank(message = "Invite code is required")
    @Size(min = 8, max = 8, message = "Invite code must be exactly 8 characters")
    private String inviteCode;
}
