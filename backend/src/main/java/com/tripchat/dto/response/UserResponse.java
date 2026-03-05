package com.tripchat.dto.response;

import com.tripchat.model.User;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * UserResponse DTO — safe user representation returned to the client.
 *
 * Never return the User entity directly:
 *   - passwordHash would leak to the client
 *   - JPA proxy objects cause serialization issues
 *   - We control exactly what the client sees
 *
 * Static factory method fromEntity(User):
 *   Pattern: Static Factory Method
 *   Keeps mapping logic here, not scattered in services.
 *   Service calls UserResponse.from(user) — clean, readable.
 */
@Getter
@Builder
public class UserResponse {

    private UUID id;
    private String email;
    private String username;
    private String displayName;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .build();
    }
}
