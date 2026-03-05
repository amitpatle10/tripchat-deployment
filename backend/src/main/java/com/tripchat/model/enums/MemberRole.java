package com.tripchat.model.enums;

/**
 * MemberRole — role a user holds within a group.
 *
 * ADMIN  = group creator; can delete group, regenerate invite code.
 *          Cannot leave — must delete the group instead (Phase 1 rule).
 * MEMBER = regular participant; can send messages, leave any time.
 *
 * Stored as STRING in DB — readable, stable across enum reordering.
 */
public enum MemberRole {
    ADMIN,
    MEMBER
}
