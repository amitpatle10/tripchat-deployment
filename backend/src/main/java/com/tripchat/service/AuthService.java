package com.tripchat.service;

import com.tripchat.dto.request.LoginRequest;
import com.tripchat.dto.request.RegisterRequest;
import com.tripchat.dto.response.AuthResponse;
import com.tripchat.security.strategy.AuthStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * AuthService — orchestrates authentication operations.
 *
 * Pattern: Strategy Pattern context class.
 * AuthService doesn't know HOW authentication works — it delegates to
 * whichever AuthStrategy is injected. Currently: EmailPasswordAuthStrategy.
 *
 * Why inject AuthStrategy interface, not EmailPasswordAuthStrategy directly:
 *   Dependency Inversion Principle — depend on abstraction, not concretion.
 *   When GoogleAuthStrategy is added, AuthService needs zero changes.
 *
 * Note on multiple strategies in the future:
 *   When we have multiple AuthStrategy beans, we'll use a Map<String, AuthStrategy>
 *   keyed by provider name, or a @Qualifier. For now, single impl = simple injection.
 *
 * @Transactional is NOT on this class — it belongs on repository/service methods
 * that span multiple DB operations. Registration is a single save — JpaRepository
 * methods are already transactional by default.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthStrategy authStrategy;

    public AuthResponse register(RegisterRequest request) {
        return authStrategy.register(request);
    }

    public AuthResponse login(LoginRequest request) {
        return authStrategy.login(request);
    }
}
