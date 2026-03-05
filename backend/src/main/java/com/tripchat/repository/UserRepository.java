package com.tripchat.repository;

import com.tripchat.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * UserRepository — data access layer for User entity.
 *
 * Pattern: Repository Pattern (DDD)
 * Abstracts all DB access behind an interface. Service layer has zero
 * knowledge of SQL, Hibernate, or connection pooling — it just calls methods.
 *
 * IgnoreCase methods:
 *   Spring Data translates these to LOWER(column) = LOWER(?) queries.
 *   Ensures "Amit@test.com" and "amit@test.com" are treated as the same email.
 *   Works in tandem with the case-insensitive DB index defined in migrations.
 *
 * Why Optional<User> over User:
 *   Forces callers to handle the "not found" case explicitly.
 *   No silent NullPointerExceptions — caller must call .orElseThrow() or check.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Used in optimistic insert path — catch DataIntegrityViolationException
    // to detect which field caused the duplicate violation
    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    // Used in login flow — find user by email to verify password
    Optional<User> findByEmailIgnoreCase(String email);

    // Used in JWT filter — load user from token's subject claim
    Optional<User> findByUsername(String username);
}
