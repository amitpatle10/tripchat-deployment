package com.tripchat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CreateGroupRequest DTO — input for POST /api/v1/groups
 *
 * name: 3-50 chars, any characters (agreed).
 * description: optional — null is valid, empty string is not (blank check).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {

    @NotBlank(message = "Group name is required")
    @Size(min = 3, max = 50, message = "Group name must be between 3 and 50 characters")
    private String name;

    // Optional — no @NotBlank. Null = no description provided.
    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}
