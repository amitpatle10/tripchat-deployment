package com.tripchat.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * RegisterRequest DTO — input contract for POST /api/v1/auth/register
 *
 * Why DTOs over exposing entities directly:
 *   Entities are JPA-managed objects — exposing them leaks DB structure,
 *   risks lazy-loading exceptions, and couples API contract to DB schema.
 *   DTOs are pure data carriers with no JPA baggage.
 *
 * Validation happens here (Bean Validation / JSR-380), not in the service.
 *   Fail fast at the boundary — service receives only valid data.
 *   GlobalExceptionHandler catches MethodArgumentNotValidException → 400.
 *
 * Password rules (Option C agreed):
 *   Min 8 chars + at least one digit + at least one special character.
 *   Regex breakdown:
 *     (?=.*\d)          — at least one digit
 *     (?=.*[@$!%*?&])   — at least one special character from the allowed set
 *     .{8,}             — minimum 8 characters total
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Pattern(
        regexp = "^(?=.*\\d)(?=.*[@$!%*?&]).{8,}$",
        message = "Password must be at least 8 characters and contain at least one number and one special character (@$!%*?&)"
    )
    private String password;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    @Pattern(
        regexp = "^[a-zA-Z0-9_]+$",
        message = "Username can only contain letters, numbers, and underscores"
    )
    private String username;

    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 30, message = "Display name must be between 2 and 30 characters")
    private String displayName;
}
