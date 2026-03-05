package com.tripchat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * TypingRequest — payload sent by the client over STOMP.
 *
 * typing = true  → user started typing
 * typing = false → user stopped typing
 *
 * Client responsibility:
 *   - Send true when the user begins typing
 *   - Send false when the user explicitly stops (clears input, sends message)
 *   - Refresh every 3s while still typing (keeps the server-side TTL alive)
 *   The server's 5s TTL acts as a dead man's switch — if the client crashes,
 *   the typing indicator automatically disappears after 5s.
 */
@Getter
@NoArgsConstructor
public class TypingRequest {

    @NotNull(message = "typing flag is required")
    private Boolean typing;
}
