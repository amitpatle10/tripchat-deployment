package com.tripchat.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LoginRequest DTO — input contract for POST /api/v1/auth/login
 *
 * Intentionally minimal validation — only blank checks.
 * No password rules here (unlike RegisterRequest) because:
 *   - Password correctness is verified by AuthenticationManager against the DB hash.
 *   - Applying regex rules on login would lock out users if rules change after registration.
 *   - We never want to tell an attacker WHICH validation rule their guess failed.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;
}
