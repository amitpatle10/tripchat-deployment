package com.tripchat.model.enums;

/**
 * AuthProvider — Extensibility hook for multiple authentication strategies.
 *
 * Pattern: Strategy Pattern support at the data layer.
 * LOCAL users have password_hash set; GOOGLE users have provider_id set.
 * Adding a new provider (GitHub, Apple) = add a new enum value + new AuthStrategy impl.
 * Zero schema migration needed for the users table.
 */
public enum AuthProvider {
    LOCAL,   // email + password, we own the credential
    GOOGLE   // OAuth2 via Google, Google owns the credential
}
