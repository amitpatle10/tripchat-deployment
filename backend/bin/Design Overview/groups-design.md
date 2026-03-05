# TripChat — Groups System Design Overview

---

## What We Built

Six APIs:

- `POST /api/v1/groups` → create a group, creator becomes ADMIN, returns GroupResponse
- `GET /api/v1/groups` → list all groups the authenticated user belongs to
- `GET /api/v1/groups/{id}` → get a single group's details (members-only)
- `POST /api/v1/groups/join` → join a group using an 8-char invite code
- `DELETE /api/v1/groups/{id}/leave` → leave a group (MEMBER only, ADMIN cannot leave)
- `POST /api/v1/groups/{id}/invite/regenerate` → generate a new invite code (ADMIN only)

---

## The Big Picture — How Classes Connect

```
HTTP Request
    │
    ▼
GroupController         ← receives request, reads authenticated user from JWT
    │
    ▼
GroupService            ← all business logic lives here
    ├── UserRepository          ← loads current user from DB
    ├── GroupRepository         ← saves/finds groups
    ├── GroupMemberRepository   ← manages who is in which group
    └── InviteCodeGenerator     ← generates secure random invite codes
```

---

## Class By Class — Simple Terms

### `GroupController`

- Front door for all group operations.
- Gets the authenticated user via `@AuthenticationPrincipal UserDetails` — Spring Security injects this automatically from the JWT token. No manual token parsing.
- Does **zero** business logic. Just routes request → service → response.
- Returns `201 Created` for new groups, `204 No Content` for leave, `200 OK` for everything else.

### `GroupService`

- Where all the decisions happen.
- Write methods (`createGroup`, `joinGroup`, `leaveGroup`, `regenerateInviteCode`) are `@Transactional` — if anything fails mid-way, the whole operation rolls back.
- Read methods (`getMyGroups`, `getGroupById`) are not transactional — read-only, no side effects.
- Enforces all business rules: max 1000 members, ADMIN cannot leave, MEMBER cannot regenerate code.

### `GroupRepository`

- Interface to the `chat_groups` table in PostgreSQL.
- `findByInviteCode(String)` → used for joining; looks up the group by its secret code.
- `existsByInviteCode(String)` → collision check during code generation.

### `GroupMemberRepository`

- Interface to the `group_members` junction table.
- `findByUser(User)` → uses a **custom JPQL query with JOIN FETCH** to load a user's groups in one SQL query (prevents N+1).
- `existsByGroupAndUser(Group, User)` → duplicate join check before inserting.
- `countByGroup(Group)` → member count for max-member enforcement and response payload.
- `findByGroupAndUser(Group, User)` → loads a specific membership (for leave/regenerate flows).

### `InviteCodeGenerator`

- Generates cryptographically random 8-character codes.
- Character set: `A-Z + 2-9` — **excludes O, 0, I, 1** to avoid visual confusion when codes are read aloud or typed manually.
- Uses `SecureRandom` (not `Math.random`) — OS entropy source, unpredictable. This matters because the invite code **is** the access control mechanism.
- One shared instance (`@Component` Singleton) — `SecureRandom` is thread-safe.

### `Group` (entity)

- Maps to the `chat_groups` table — named this way because `groups` is a **reserved keyword** in SQL.
- Key fields: `name`, `description` (optional), `createdBy` (FK to User), `inviteCode` (unique, 8 chars), `isActive` (soft delete).
- `createdBy` is `FetchType.LAZY` — the full User object is not loaded unless explicitly needed. Prevents unnecessary joins.

### `GroupMember` (entity)

- Junction table between `User` and `Group` — models the many-to-many relationship.
- Has its own UUID primary key (not a composite key of group_id + user_id).
  - Composite keys in JPA require `@EmbeddedId` or `@IdClass` — more boilerplate.
  - UUID PK is simpler; uniqueness is still enforced via `@UniqueConstraint`.
- Stores `role` (`ADMIN` or `MEMBER`) and `joinedAt` timestamp.
- Both `group` and `user` associations are `FetchType.LAZY`.

### `MemberRole` (enum)

- Two values: `ADMIN` and `MEMBER`.
- Stored as `STRING` in the DB (`@Enumerated(EnumType.STRING)`) — readable, no magic numbers.
- ADMIN: can regenerate invite code, cannot leave (must delete group).
- MEMBER: can leave, cannot regenerate code.

### `GroupResponse` (DTO)

- What the client receives: `id`, `name`, `description`, `inviteCode`, `memberCount`, `myRole`, `createdBy` (UUID), `createdAt`.
- Created via static factory method `GroupResponse.from(Group, GroupMember, int)` — mapping logic is in the DTO, not scattered in the service.
- `myRole` — the calling user's role in the group (personalized per request).

---

## Database Schema

```
chat_groups
  id           UUID  (PK)
  name         VARCHAR(50)   NOT NULL
  description  VARCHAR(500)  NULLABLE
  created_by   UUID          FK → users.id
  invite_code  VARCHAR(8)    UNIQUE NOT NULL
  is_active    BOOLEAN       NOT NULL  DEFAULT true
  created_at   TIMESTAMP     NOT NULL
  updated_at   TIMESTAMP     NOT NULL

  Indexes:
    idx_groups_invite_code  (invite_code)   UNIQUE
    idx_groups_created_by   (created_by)

group_members
  id        UUID  (PK)
  group_id  UUID  FK → chat_groups.id   NOT NULL
  user_id   UUID  FK → users.id         NOT NULL
  role      VARCHAR(10)  NOT NULL  (ADMIN | MEMBER)
  joined_at TIMESTAMP    NOT NULL

  Constraints:
    uk_group_members_group_user  UNIQUE (group_id, user_id)

  Indexes:
    idx_group_members_user   (user_id)
    idx_group_members_group  (group_id)
```

---

## Create Group — Step by Step

```
1. POST /api/v1/groups  { name, description }
         │
2. @Valid checks input
   → blank name?              400 Bad Request
   → name > 50 chars?         400 Bad Request
   → description > 500 chars? 400 Bad Request
         │
3. GroupService.createGroup()
   → load current user from DB (email from JWT)
   → generateUniqueCode()
       loop:
         generate 8-char code via InviteCodeGenerator
         if code already exists in DB → retry
       (collision is astronomically rare at 1000 groups)
         │
4. Save Group to chat_groups
         │
5. Save GroupMember to group_members
   → role = ADMIN, joined_at = now
   → both saves are @Transactional — if either fails, both roll back
         │
6. 201 Created
   { id, name, description, inviteCode, memberCount: 1, myRole: "ADMIN", createdBy, createdAt }
```

---

## Join Group — Step by Step

```
1. POST /api/v1/groups/join  { inviteCode }
         │
2. @Valid checks input
   → blank code?          400 Bad Request
   → not exactly 8 chars? 400 Bad Request
         │
3. GroupService.joinGroup()
   → load current user from DB
   → find group by inviteCode
         │
   ┌─────┴────────────────────────────┐
   │ code not found OR group inactive │ code found AND group active
   ▼                                  ▼
   InvalidInviteCodeException    check membership
   → 404 Not Found               already a member?
   (404, not 403 — security:         │
    don't confirm group exists)   ┌──┴─────────────┐
                                  │ yes             │ no
                                  ▼                 ▼
                            AlreadyMember    check member count
                            Exception        ≥ 1000?
                            → 409               │
                                           ┌────┴──────────┐
                                           │ yes            │ no
                                           ▼                ▼
                                      GroupFull      save GroupMember
                                      Exception      role = MEMBER
                                      → 400               │
                                                     4. 200 OK
                                                        { myRole: "MEMBER",
                                                          memberCount: N+1 }
```

---

## Leave Group — Step by Step

```
1. DELETE /api/v1/groups/{id}/leave
         │
2. GroupService.leaveGroup()
   → load current user from DB
   → find group by ID
         │
   ┌─────┴─────────────────────────┐
   │ not found OR inactive         │ found
   ▼                               ▼
   GroupNotFoundException    find membership for this user
   → 404                          │
                             ┌────┴────────────────┐
                             │ not a member         │ is a member
                             ▼                      ▼
                        NotMember           check role
                        Exception              │
                        → 404          ┌───────┴───────────┐
                        (security:     │ ADMIN              │ MEMBER
                         404 not 403,  ▼                    ▼
                         don't reveal  AdminCannot     delete membership
                         membership)   LeaveException      │
                                       → 400           3. 204 No Content
```

---

## Regenerate Invite Code — Step by Step

```
1. POST /api/v1/groups/{id}/invite/regenerate
         │
2. GroupService.regenerateInviteCode()
   → load current user from DB
   → find group by ID (404 if not found/inactive)
   → find membership for this user (404 if not member)
         │
   ┌─────┴──────────────────────────┐
   │ MEMBER role                    │ ADMIN role
   ▼                                ▼
   UnauthorizedGroupAction     generateUniqueCode()
   Exception                        │
   → 403 Forbidden             save group with new code
                                    │
                               old code is immediately invalid
                               existing members are NOT kicked out
                               (code change only affects new joiners)
                                    │
                               3. 200 OK  { inviteCode: "NEWCODE1", ... }
```

---

## How Classes Relate (Has-A Relationships)

```
GroupController
  └── has-a ──► GroupService

GroupService
  ├── has-a ──► GroupRepository
  ├── has-a ──► GroupMemberRepository
  ├── has-a ──► UserRepository
  └── has-a ──► InviteCodeGenerator

GroupRepository
  └── manages ──► Group entity

GroupMemberRepository
  └── manages ──► GroupMember entity

Group
  ├── has-a ──► User (createdBy, LAZY)
  └── is-tracked-by ──► GroupMember (via group_id FK)

GroupMember
  ├── has-a ──► Group (LAZY)
  ├── has-a ──► User  (LAZY)
  └── has-a ──► MemberRole enum (ADMIN | MEMBER)

GroupResponse
  └── built-from ──► Group + GroupMember + memberCount
```

---

## Design Patterns Used

| Pattern                     | Where                                         | Why                                                                       |
| --------------------------- | --------------------------------------------- | ------------------------------------------------------------------------- |
| **Repository (DDD)**        | `GroupRepository`, `GroupMemberRepository`    | Service talks to an interface — swappable, testable with mocks            |
| **Static Factory**          | `GroupResponse.from(Group, GroupMember, int)` | Mapping logic lives in the DTO, not scattered across services             |
| **Builder**                 | `Group.builder()`, `GroupMember.builder()`    | Readable construction of multi-field objects without giant constructors   |
| **Singleton**               | `InviteCodeGenerator` (`@Component`)          | One `SecureRandom` instance shared safely across all requests             |
| **Decorator (AOP)**         | `ExecutionTimeAspect`                         | Wraps every service method with timing — group service has zero awareness |
| **Chain of Responsibility** | `GlobalExceptionHandler`                      | Each exception maps to one handler, no try-catch in service code          |

---

## Security Decisions

| Decision               | What We Do                         | Why                                                              |
| ---------------------- | ---------------------------------- | ---------------------------------------------------------------- |
| Group discovery        | No public group listing            | Groups are private by design — invite-only                       |
| Wrong invite code      | Returns `404 Not Found`            | Attacker can't tell if the code is wrong vs group doesn't exist  |
| Non-member GET group   | Returns `404 Not Found`            | Don't reveal that the group exists at all                        |
| Non-member regenerate  | Returns `404 Not Found`            | Same principle — don't leak group existence                      |
| MEMBER regenerate      | Returns `403 Forbidden`            | User is confirmed a member, so existence is OK to reveal         |
| Invite code randomness | `SecureRandom` (OS entropy)        | `Math.random()` is predictable — attackers could enumerate codes |
| Character set          | Excludes O, 0, I, 1                | Prevents visual confusion on manual entry — UX + correctness     |
| Code collision         | Retry loop                         | Extremely rare; loop is a safety net (32^8 ≈ 1 trillion combos)  |
| Soft delete            | `isActive = false`                 | Preserves message history and audit trail                        |
| ADMIN cannot leave     | Throws `AdminCannotLeaveException` | Prevents groups from becoming orphaned without an admin          |

---

## N+1 Problem — What It Is and How We Prevented It

**The problem:**

When loading a user's groups, a naive approach would:

1. `SELECT * FROM group_members WHERE user_id = ?` → returns 5 rows
2. For each row, `SELECT * FROM chat_groups WHERE id = ?` → 5 more queries
3. Total: 6 queries. For 100 groups: 101 queries. This is the N+1 problem.

**Our fix — JOIN FETCH:**

```sql
-- What GroupMemberRepository.findByUser() actually does:
SELECT gm.*, g.*
FROM group_members gm
JOIN chat_groups g ON g.id = gm.group_id
WHERE gm.user_id = :userId
  AND g.is_active = true
```

One query, all data. N groups = 1 query always.

**What's still N+1 (accepted tradeoff):**

`countByGroup()` is called once per group when building `GroupResponse`. At 10 groups → 10 count queries. Acceptable at Phase 1 scale. Will be moved to Redis cache later.

---

## Transactions — Why They Matter Here

`createGroup` saves two rows — one in `chat_groups`, one in `group_members`. Without `@Transactional`:

```
1. Save group    ← succeeds
2. Save member   ← fails (e.g., DB connection drops)

Result: orphan group with no admin. Unreachable, undeletable.
```

With `@Transactional`:

```
1. Save group    ← succeeds (in same transaction)
2. Save member   ← fails
→ entire transaction rolls back
→ no orphan group, clean state
```

Same logic applies to `joinGroup` — the member count check and the insert must be atomic. Without it, two concurrent requests could both pass the `≥ 1000` check and both insert, exceeding the limit.

---

## DTOs — What Goes In, What Comes Out

```
CreateGroupRequest (IN)          JoinGroupRequest (IN)
  name        → @NotBlank          inviteCode  → @NotBlank
                @Size(3-50)                       @Size(min=8, max=8)
  description → optional
                @Size(max=500)

GroupResponse (OUT) — same for all 5 endpoints that return a group
  id          → UUID
  name        → String
  description → String (nullable)
  inviteCode  → 8-char String     ← only visible to members
  memberCount → int
  myRole      → MemberRole (ADMIN | MEMBER)
  createdBy   → UUID (creator's user id)
  createdAt   → ISO-8601 timestamp
```

---

## AOP Timings (Real Results)

| Operation              | Time   | Notes                                    |
| ---------------------- | ------ | ---------------------------------------- |
| `createGroup`          | 8ms    | Two DB writes in one transaction         |
| `joinGroup`            | 5–10ms | Three DB reads + one write               |
| `leaveGroup`           | 7ms    | Two DB reads + one delete                |
| `regenerateInviteCode` | 10ms   | Three DB reads + one write               |
| `getMyGroups` (cold)   | 37ms   | First call — JPA initialisation overhead |
| `getGroupById`         | 4–19ms | Two DB reads                             |

All within the 20ms target after warm-up. `getMyGroups` cold start is a JPA warmup artefact — acceptable.

---

## File Map

```
src/main/java/com/tripchat/
│
├── controller/
│   └── GroupController.java            ← 6 endpoints, @AuthenticationPrincipal
│
├── service/
│   └── GroupService.java               ← all business logic, @Transactional on writes
│
├── repository/
│   ├── GroupRepository.java            ← chat_groups table access
│   └── GroupMemberRepository.java      ← group_members table + JOIN FETCH query
│
├── model/
│   ├── Group.java                      ← chat_groups entity
│   ├── GroupMember.java                ← junction table entity
│   └── enums/MemberRole.java           ← ADMIN | MEMBER
│
├── dto/
│   ├── request/
│   │   ├── CreateGroupRequest.java     ← name + optional description
│   │   └── JoinGroupRequest.java       ← inviteCode (exactly 8 chars)
│   └── response/
│       └── GroupResponse.java          ← unified response for all group endpoints
│
├── exception/
│   ├── GlobalExceptionHandler.java     ← maps all exceptions to HTTP responses
│   ├── GroupNotFoundException.java     ← 404
│   ├── InvalidInviteCodeException.java ← 404 (same as not found — security)
│   ├── AlreadyMemberException.java     ← 409
│   ├── GroupFullException.java         ← 400
│   ├── NotMemberException.java         ← 404
│   ├── AdminCannotLeaveException.java  ← 400
│   └── UnauthorizedGroupActionException.java ← 403
│
└── util/
    └── InviteCodeGenerator.java        ← SecureRandom, 32-char set, 8 chars

src/test/java/com/tripchat/
└── service/
    └── GroupServiceTest.java           ← 15 tests across 5 @Nested classes
```

---

## What's Intentionally Not Built (Phase 1 Scope)

| Feature                  | Reason Deferred                                                |
| ------------------------ | -------------------------------------------------------------- |
| Group deletion           | Need to handle message history, member notifications — Phase 2 |
| Transfer ADMIN role      | Low priority; Phase 2                                          |
| Invite code expiry       | Fixed code is sufficient at this scale; Phase 2                |
| Member count Redis cache | N+1 on count is acceptable at 1000 DAUs; Phase 2               |
| Ban/kick members         | Phase 2                                                        |
| Public group search      | By design — groups are invite-only                             |
