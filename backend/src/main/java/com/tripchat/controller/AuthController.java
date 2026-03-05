package com.tripchat.controller;

import com.tripchat.dto.request.LoginRequest;
import com.tripchat.dto.request.RegisterRequest;
import com.tripchat.dto.response.AuthResponse;
import com.tripchat.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AuthController — HTTP entry point for authentication operations.
 *
 * RESTful conventions:
 *   POST /api/v1/auth/register → 201 Created
 *   POST /api/v1/auth/login    → 200 OK (Phase 2)
 *
 * /api/v1/ versioning:
 *   Breaking API changes → /api/v2/. Existing clients on /v1/ are unaffected.
 *
 * @Valid on @RequestBody:
 *   Triggers Bean Validation on RegisterRequest before the method body runs.
 *   Validation failures throw MethodArgumentNotValidException → caught by
 *   GlobalExceptionHandler → 400 Bad Request with field-level errors.
 *
 * Controller is thin — no business logic here.
 *   Receives request → passes to service → returns response.
 *   All logic lives in AuthService and EmailPasswordAuthStrategy.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/register
     * Register a new user and return a JWT immediately.
     * 201 Created on success, 400 on validation failure, 409 on duplicate.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/v1/auth/login
     * Authenticate an existing user and return a JWT.
     * 200 OK on success, 400 on blank fields, 401 on wrong credentials.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }
}
