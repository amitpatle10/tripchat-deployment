# Database Schema Design — TripChat Messaging

## Tables Overview

Two new tables added for messaging on top of existing (`users`, `chat_groups`, `group_members`):

1. **`messages`** — permanent store for all messages
2. **`outbox`** — transient relay table for the cold path pipeline

---

## Full Schema Diagram

```
┌──────────────────────────────┐         ┌──────────────────────────────────┐
│           users              │         │           chat_groups             │
├──────────────────────────────┤         ├──────────────────────────────────┤
│ id            UUID PK        │         │ id            UUID PK             │
│ username      VARCHAR UNIQUE │         │ name          VARCHAR             │
│ email         VARCHAR UNIQUE │         │ description   TEXT                │
│ password_hash VARCHAR        │         │ invite_code   VARCHAR UNIQUE      │
│ display_name  VARCHAR        │         │ created_by    UUID FK → users.id  │
│ created_at    TIMESTAMPTZ    │         │ created_at    TIMESTAMPTZ         │
└──────────────┬───────────────┘         └────────────┬─────────────────────┘
               │                                      │
               │                    ┌─────────────────┘
               │                    │
               │         ┌──────────▼───────────────────────┐
               │         │         group_members             │
               │         ├──────────────────────────────────┤
               │         │ id          UUID PK               │
               │         │ group_id    UUID FK → chat_groups │
               └────────►│ user_id     UUID FK → users       │
                         │ role        ENUM (ADMIN/MEMBER)   │
                         │ joined_at   TIMESTAMPTZ           │
                         └──────────────────────────────────┘

               │                    │
               │   (sender_id)      │   (group_id)
               │                    │
         ┌─────▼────────────────────▼──────────────────┐
         │                 messages                     │
         ├─────────────────────────────────────────────┤
         │ id           UUID PK                         │
         │ group_id     UUID FK → chat_groups CASCADE   │◄── group deleted → messages deleted
         │ sender_id    UUID FK → users SET NULL        │◄── user deleted → sender_id = null
         │ content      TEXT NOT NULL                   │
         │ client_id    UUID UNIQUE                     │◄── idempotency key (client-generated)
         │ created_at   TIMESTAMPTZ NOT NULL            │
         │ deleted_at   TIMESTAMPTZ                     │◄── null = active, timestamp = deleted
         └─────────────────────────────────────────────┘

         ┌─────────────────────────────────────────────┐
         │                  outbox                     │
         ├─────────────────────────────────────────────┤
         │ id            UUID PK                        │
         │ group_id      UUID FK → chat_groups CASCADE  │◄── group deleted → relay skips cleanly
         │ sender_id     UUID (no FK)                   │◄── relay needs value only; messages handles SET NULL
         │ content       TEXT NOT NULL                  │
         │ client_id     UUID UNIQUE                    │◄── dedup on write
         │ status        VARCHAR DEFAULT 'PENDING'      │◄── PENDING → PUBLISHED → FAILED
         │ retry_count   INTEGER DEFAULT 0              │◄── 3 retries → FAILED → DLQ
         │ created_at    TIMESTAMPTZ NOT NULL           │
         │ published_at  TIMESTAMPTZ                    │◄── set when relay publishes to Kafka
         └─────────────────────────────────────────────┘
```

---

## DDL

```sql
CREATE TABLE messages (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID        NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
    sender_id   UUID        REFERENCES users(id) ON DELETE SET NULL,
    content     TEXT        NOT NULL,
    client_id   UUID        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE outbox (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id      UUID        NOT NULL REFERENCES chat_groups(id) ON DELETE CASCADE,
    sender_id     UUID        NOT NULL,
    content       TEXT        NOT NULL,
    client_id     UUID        NOT NULL UNIQUE,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count   INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
```

---

## Indexes

```sql
-- messages: covers the most frequent query — get recent messages for a group
-- partial index: only indexes non-deleted rows → smaller, faster
CREATE INDEX idx_messages_group_created
ON messages (group_id, created_at DESC)
WHERE deleted_at IS NULL;

-- messages: client_id uniqueness already indexed via UNIQUE constraint

-- outbox: covers relay polling query — only indexes PENDING rows
-- as rows move to PUBLISHED they fall out of this index automatically
CREATE INDEX idx_outbox_status_created
ON outbox (status, created_at ASC)
WHERE status = 'PENDING';
```

---

## FK Behaviour Rationale

| FK | On Delete | Why |
|--|--|--|
| `messages.group_id` → `chat_groups` | CASCADE | Group deleted → all messages deleted |
| `messages.sender_id` → `users` | SET NULL | User deleted → messages stay, sender shown as "Deleted User" |
| `outbox.group_id` → `chat_groups` | CASCADE | Group deleted → PENDING rows cleaned up → relay skips cleanly, no DLQ noise |
| `outbox.sender_id` | No FK | Relay only needs the UUID value for Kafka payload; consumer handles SET NULL on messages side |

### Why outbox.group_id needs CASCADE (not no FK)

Without FK — group deleted, but PENDING outbox rows remain:
```
Relay publishes to Kafka
Kafka consumer tries INSERT into messages
messages.group_id FK fails — group doesn't exist
After 3 retries → DLQ → orphaned message, wasted work
```

With FK CASCADE — group deleted → outbox rows deleted:
```
Relay wakes up → nothing to process → clean
```

---

## Normalization Decision

Messages store `sender_id` only — no denormalization of sender name or avatar.

- JOIN with users table on read (single query, no N+1)
- Always up to date — user changes display name → all messages reflect it
- Denormalization would cause stale sender names with no meaningful performance benefit at our scale

---

## Cursor-Based Pagination

Why not offset?
```sql
-- OFFSET scans and discards N rows before returning results
-- Gets slower the deeper you paginate — unusable at millions of messages
SELECT * FROM messages WHERE group_id = ? ORDER BY created_at DESC
OFFSET 100000 LIMIT 50;  ← scans 100,000 rows
```

Cursor-based — jumps directly to position using index:
```sql
-- First page (no cursor)
SELECT * FROM messages
WHERE group_id = :groupId AND deleted_at IS NULL
ORDER BY created_at DESC, id DESC
LIMIT 50;

-- Next page (cursor = last message's created_at + id)
SELECT * FROM messages
WHERE group_id = :groupId
  AND deleted_at IS NULL
  AND (created_at, id) < (:cursorTime, :cursorId)
ORDER BY created_at DESC, id DESC
LIMIT 50;
```

Composite cursor `(created_at, id)` — two messages can share the same millisecond timestamp. `id` (UUID) breaks the tie and makes the cursor always unique.

---

## N+1 Prevention

Loading 50 messages then fetching each sender separately = 51 queries. Prevented with a single JOIN FETCH query:

```java
@Query("""
    SELECT m FROM Message m
    JOIN FETCH m.sender
    WHERE m.group.id = :groupId
      AND m.deletedAt IS NULL
      AND (:cursorTime IS NULL OR (m.createdAt, m.id) < (:cursorTime, :cursorId))
    ORDER BY m.createdAt DESC, m.id DESC
    """)
List<Message> findMessages(...);
```

One query. Always.

---

## Outbox Cleanup

PUBLISHED records are dead weight. Nightly cleanup job:
```sql
DELETE FROM outbox
WHERE status = 'PUBLISHED'
AND published_at < now() - INTERVAL '7 days';
```

Keeps the outbox table small regardless of message volume.

---

## Final Decisions

| Decision | Choice |
|--|--|
| Soft delete | `deleted_at TIMESTAMPTZ` — null = active, timestamp = deleted |
| Group delete behaviour | CASCADE on both `messages` and `outbox` |
| User delete behaviour | SET NULL on `messages.sender_id`; no FK on `outbox.sender_id` |
| Pagination | Cursor-based on `(created_at DESC, id DESC)` |
| Normalization | Normalized — JOIN with users, no denormalization |
| N+1 prevention | JOIN FETCH in repository query |
| Outbox cleanup | Nightly delete of PUBLISHED records older than 7 days |
