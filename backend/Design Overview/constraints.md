# TripChat — Phase 1 Constraints

> Everything the system enforces today, the numbers behind every limit,
> and what is intentionally deferred to Phase 2.

---

## Scale Targets

| Metric                           | Phase 1 Target                     |
| -------------------------------- | ---------------------------------- |
| Daily Active Users (DAUs)        | 1,000                              |
| Concurrent WebSocket connections | ~200 (20% of DAUs active at peak)  |
| Groups per user                  | Unlimited (no enforced cap)        |
| Messages per day                 | ~50,000 (50 msg/user/day estimate) |
| DB connection pool size          | 10                                 |

---

## User Constraints

### Registration

| Field        | Rule                                                    | Error if violated |
| ------------ | ------------------------------------------------------- | ----------------- |
| Email        | Valid email format, not blank                           | 400 Bad Request   |
| Email        | Case-insensitive unique across all users                | 409 Conflict      |
| Password     | Minimum 8 characters                                    | 400 Bad Request   |
| Password     | At least 1 digit (0–9)                                  | 400 Bad Request   |
| Password     | At least 1 special character (`@$!%*?&`)                | 400 Bad Request   |
| Username     | 3–50 characters                                         | 400 Bad Request   |
| Username     | Only letters, numbers, and underscore (`[a-zA-Z0-9_]+`) | 400 Bad Request   |
| Username     | Case-insensitive unique across all users                | 409 Conflict      |
| Display name | 2–30 characters                                         | 400 Bad Request   |
| Display name | Any characters allowed (not unique)                     | —                 |

### Account Behaviour

| Rule               | Detail                                                    |
| ------------------ | --------------------------------------------------------- |
| Passwords stored   | BCrypt hash only — raw password never persisted           |
| BCrypt cost factor | 10 (~100–290ms intentionally — brute force resistance)    |
| Account deletion   | Soft delete only (`isActive = false`) — hard delete never |
| Auth provider      | `LOCAL` (email+password) or `GOOGLE` — stored per user    |

---

## Session / JWT Constraints

| Parameter              | Value                                                     | Reason                                         |
| ---------------------- | --------------------------------------------------------- | ---------------------------------------------- |
| Token type             | Bearer JWT                                                | Stateless — no server-side session storage     |
| Signing algorithm      | HMAC-SHA (jjwt 0.12.x auto-selects based on key size)     | Symmetric, fast                                |
| Token expiry           | 24 hours (86,400,000 ms)                                  | Balance between UX (fewer logins) and security |
| Token contents         | `email`, `userId`, `username`, `iat`, `exp`               | Minimum needed                                 |
| Token does NOT contain | `passwordHash`, `isActive`, `displayName`, `authProvider` | Security / staleness                           |
| Secret key source      | Environment variable `JWT_SECRET` — never hardcoded       | Secret management                              |
| Refresh tokens         | Not implemented in Phase 1                                | Phase 2                                        |

---

## Group Constraints

### Size Limits

| Limit                     | Value                           | Where enforced                             |
| ------------------------- | ------------------------------- | ------------------------------------------ |
| Maximum members per group | **1,000**                       | `GroupService.MAX_MEMBERS` constant        |
| Group name length         | 3–50 characters                 | `@Size` validation on `CreateGroupRequest` |
| Group description length  | Up to 500 characters (optional) | `@Size` validation on `CreateGroupRequest` |
| Group name                | Not blank                       | `@NotBlank` validation                     |

### Invite Code

| Property           | Value                                      | Reason                                           |
| ------------------ | ------------------------------------------ | ------------------------------------------------ |
| Length             | **8 characters**                           | Short enough to type, enough entropy             |
| Character set      | `A–Z + 2–9` (32 chars)                     | Excludes O, 0, I, 1 to avoid visual confusion    |
| Total combinations | 32^8 = **1,099,511,627,776** (~1 trillion) | Collision probability negligible at 1,000 groups |
| Generation method  | `SecureRandom` (OS entropy)                | Unpredictable — codes cannot be enumerated       |
| Collision handling | Retry loop until unique code found         | Safety net — not an expected path                |
| Code scope         | One active code per group at a time        | Old code is instantly invalid after regeneration |
| Code expiry        | No expiry in Phase 1                       | Fixed code is sufficient; expiry is Phase 2      |

### Membership Rules

| Rule                           | Detail                                           | HTTP status       |
| ------------------------------ | ------------------------------------------------ | ----------------- |
| Join by invite code only       | No public group listing or discovery             | 404 if code wrong |
| Cannot join twice              | Duplicate membership check before insert         | 409 Conflict      |
| Max member enforcement         | Check `countByGroup` before inserting new member | 400 Bad Request   |
| ADMIN cannot leave             | Must delete group instead                        | 400 Bad Request   |
| MEMBER can leave               | No restrictions                                  | 204 No Content    |
| Only ADMIN can regenerate code | Role check before update                         | 403 Forbidden     |
| Non-member GET group           | 404 (not 403) — group existence not revealed     | 404 Not Found     |

### Roles

| Role     | Capabilities                                    |
| -------- | ----------------------------------------------- |
| `ADMIN`  | Regenerate invite code; all MEMBER capabilities |
| `MEMBER` | View group details, leave group                 |

> There is only one ADMIN per group (the creator). Role transfer is Phase 2.

---

## Input Validation Constraints (All Endpoints)

### POST /api/v1/auth/register

| Field         | Constraint                                          |
| ------------- | --------------------------------------------------- |
| `email`       | `@Email`, `@NotBlank`                               |
| `password`    | `@Pattern(^(?=.*\d)(?=.*[@$!%*?&]).{8,}$)`          |
| `username`    | `@Pattern(^[a-zA-Z0-9_]+$)`, `@Size(min=3, max=50)` |
| `displayName` | `@Size(min=2, max=30)`, `@NotBlank`                 |

### POST /api/v1/auth/login

| Field      | Constraint                                                |
| ---------- | --------------------------------------------------------- |
| `email`    | `@Email`, `@NotBlank`                                     |
| `password` | `@NotBlank` (no strength rules — DB will reject if wrong) |

### POST /api/v1/groups

| Field         | Constraint                             |
| ------------- | -------------------------------------- |
| `name`        | `@NotBlank`, `@Size(min=3, max=50)`    |
| `description` | Optional; `@Size(max=500)` if provided |

### POST /api/v1/groups/join

| Field        | Constraint                         |
| ------------ | ---------------------------------- |
| `inviteCode` | `@NotBlank`, `@Size(min=8, max=8)` |

---

## Infrastructure Constraints

### PostgreSQL Connection Pool (HikariCP)

| Parameter               | Value                 | Impact                                  |
| ----------------------- | --------------------- | --------------------------------------- |
| Max pool size           | **10**                | Max 10 concurrent DB queries            |
| Min idle connections    | 2                     | Always-warm connections                 |
| Connection timeout      | 30,000 ms (30s)       | Request fails fast if pool is exhausted |
| Idle timeout            | 600,000 ms (10 min)   | Releases unused connections             |
| Max connection lifetime | 1,800,000 ms (30 min) | Prevents stale connections              |

> At 1,000 DAUs, 10 connections is sufficient. A typical request uses a connection for < 20ms.
> Under load: 10 connections × 50 req/s capacity per connection = 500 req/s max throughput.

### Redis (Lettuce Client)

| Parameter                  | Value                |
| -------------------------- | -------------------- |
| Connection timeout         | 2,000 ms (fail fast) |
| Connection pool max-active | 8                    |
| Connection pool max-idle   | 4                    |
| Connection pool min-idle   | 1                    |

### Kafka Producer

| Parameter        | Value              | Reason                                          |
| ---------------- | ------------------ | ----------------------------------------------- |
| `acks`           | `all`              | Wait for all in-sync replicas — no message loss |
| `retries`        | 3                  | Retry transient failures automatically          |
| Key serializer   | `StringSerializer` | Message key is group ID (String)                |
| Value serializer | `JsonSerializer`   | Message payload as JSON                         |

### Kafka Consumer

| Parameter         | Value                                            |
| ----------------- | ------------------------------------------------ |
| Consumer group ID | `tripchat-group`                                 |
| Auto offset reset | `earliest` (read from beginning on new consumer) |
| Trusted packages  | `com.tripchat.*`                                 |

---

## Latency Targets

| Operation                       | Our Target | WhatsApp Reference | Current Actual               |
| ------------------------------- | ---------- | ------------------ | ---------------------------- |
| Server-side processing          | < 20ms     | < 5ms              | 4–19ms (warm)                |
| Send → all members receive      | < 100ms    | ~100ms             | TBD (Kafka phase)            |
| Load messages (Redis cache hit) | < 1ms      | < 1ms              | TBD (Redis phase)            |
| Load messages (cache miss, DB)  | < 15ms     | —                  | TBD (Redis phase)            |
| Auth (register/login)           | ~100–290ms | —                  | 97–289ms (BCrypt — expected) |

> Auth latency is intentionally high — BCrypt cost 10 is the brute-force protection.
> It is not a bug and will not be optimised.

---

## Data Retention

| Data              | Retention                            | Reason                                |
| ----------------- | ------------------------------------ | ------------------------------------- |
| User accounts     | Indefinite (soft delete only)        | Preserve message attribution          |
| Groups            | Indefinite (soft delete only)        | Preserve message history              |
| Group memberships | Until member leaves or group deleted | Active relationship                   |
| Messages          | Indefinite in Phase 1                | Infinite scroll requires full history |
| JWT tokens        | 24 hours (client-side only)          | No server-side revocation in Phase 1  |

---

## Security Boundaries

| Boundary               | Enforcement                                             |
| ---------------------- | ------------------------------------------------------- |
| All non-auth endpoints | Require valid JWT (`Authorization: Bearer <token>`)     |
| Group data             | Only visible to current group members                   |
| Invite code            | Required to join; 8-char SecureRandom                   |
| Password               | Never returned in any API response                      |
| Error messages         | Generic for auth failures (user enumeration prevention) |
| Group existence        | Not revealed to non-members (always 404, never 403)     |
| CSRF                   | Disabled — JWT in header, no cookies                    |
| Session                | Stateless — no server-side session                      |

---

## What Is NOT Enforced in Phase 1

| Constraint                                | Status                               | Planned Phase             |
| ----------------------------------------- | ------------------------------------ | ------------------------- |
| Rate limiting (login attempts, API calls) | Not implemented                      | Phase 2                   |
| Invite code expiry                        | No expiry — fixed until regenerated  | Phase 2                   |
| Role transfer (ADMIN → MEMBER)            | Not implemented                      | Phase 2                   |
| Group deletion                            | Not implemented                      | Phase 2                   |
| Kick / ban member                         | Not implemented                      | Phase 2                   |
| Token revocation / logout                 | Not implemented (token just expires) | Phase 2                   |
| Refresh tokens                            | Not implemented                      | Phase 2                   |
| Groups per user limit                     | No cap                               | Phase 2 (if needed)       |
| Message size limit                        | Not yet defined                      | Phase 2 (WebSocket phase) |
| File/image uploads                        | Not in scope                         | Phase 2                   |
| Account deletion (hard delete)            | Not in scope                         | Phase 2                   |
