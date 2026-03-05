package com.tripchat.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Global Exception Handler — Chain of Responsibility Pattern
 *
 * Pattern: Chain of Responsibility
 * Why here: Instead of try-catch in every controller, exceptions bubble up
 * and are caught here centrally. Each @ExceptionHandler is a handler in the
 * chain — Spring picks the most specific match.
 * Alternative: Per-controller @ExceptionHandler (duplicated code, inconsistent responses).
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 * Applies to all controllers. Returns JSON error responses automatically.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid / @Validated failures on request DTOs.
     * Returns field-level error messages — "email: must not be blank" etc.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors));
    }

    /**
     * 401 Unauthorized — login failed (wrong password, unknown email, inactive account).
     * Generic message intentionally — prevents user enumeration attacks.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorBody(HttpStatus.UNAUTHORIZED, ex.getMessage(), null));
    }

    /**
     * 404 Not Found — group doesn't exist, invalid invite code, non-member access, or message not found.
     * NotMemberException intentionally returns 404 (not 403) — don't confirm group existence.
     */
    @ExceptionHandler({GroupNotFoundException.class, InvalidInviteCodeException.class, NotMemberException.class, MessageNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFoundException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody(HttpStatus.NOT_FOUND, ex.getMessage(), null));
    }

    /**
     * 400 Bad Request — group is full or admin tries to leave.
     */
    @ExceptionHandler({GroupFullException.class, AdminCannotLeaveException.class})
    public ResponseEntity<Map<String, Object>> handleBadGroupActionException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(HttpStatus.BAD_REQUEST, ex.getMessage(), null));
    }

    /**
     * 403 Forbidden — member tries to perform admin-only action.
     */
    @ExceptionHandler(UnauthorizedGroupActionException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorizedGroupAction(UnauthorizedGroupActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, ex.getMessage(), null));
    }

    /**
     * 409 Conflict — email/username already exists, or already a group member.
     */
    @ExceptionHandler({EmailAlreadyExistsException.class, UsernameAlreadyTakenException.class, AlreadyMemberException.class})
    public ResponseEntity<Map<String, Object>> handleConflictException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody(HttpStatus.CONFLICT, ex.getMessage(), null));
    }

    /**
     * Catch-all for unhandled exceptions.
     * Returns 500 without leaking internal details to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", null));
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private Map<String, Object> errorBody(HttpStatus status, String message, Object details) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (details != null) body.put("details", details);
        return body;
    }
}
