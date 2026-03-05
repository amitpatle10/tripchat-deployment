# Unread Count Design

> How many messages has the user missed since they last opened a group?

---

## The Problem

When Alice is away, Bob sends 5 messages in "Trip to Goa". When Alice comes back and looks at her group list, she should see a badge showing **5 unread**. When she opens the group, the badge resets to 0.

---

## How It Works — Simple View

```
Bob sends a message
      │
      ▼
Kafka consumer persists message to DB
      │
      ├── For each group member (except Bob)
      │     HINCRBY unread:{memberId} {groupId} 1
      │
      │   Alice's Redis hash:
      │     unread:alice-uuid  →  { "trip-group-id": 5 }
      │
      │
Alice opens the app
      │
      ├── GET /api/v1/groups
      │     HGETALL unread:alice-uuid  →  { "trip-group-id": 5 }
      │     GroupResponse includes unreadCount: 5
      │
      ├── Alice opens "Trip to Goa"
      │
      └── POST /api/v1/groups/{groupId}/read
            HDEL unread:alice-uuid  trip-group-id
            unreadCount resets to 0
```

---

## Redis Data Structure — Hash

```
Key:    unread:{userId}
Type:   Hash
Fields: { groupId → count }

Example — Alice has 5 unread in "Trip to Goa", 2 in "Work":
  unread:alice-uuid  →  {
    "a2159ae9-...-trip":  5,
    "f9c3ab12-...-work":  2
  }
```

**Why Hash over individual String keys per group?**

If we used one String key per group per user:
```
unread:alice-uuid:trip-group-id  →  "5"
unread:alice-uuid:work-group-id  →  "2"
```

Loading unread counts for a user with 10 groups = **10 Redis round-trips**.

With a Hash, `HGETALL unread:alice-uuid` = **1 round-trip** for all groups.

---

## Why HINCRBY (Not Get-Then-Set)

`HINCRBY` is a single atomic Redis command. This matters when two messages arrive at the same time for the same user+group.

```
With HINCRBY (atomic):
  Thread A: HINCRBY unread:alice groupId 1  →  count: 0 → 1
  Thread B: HINCRBY unread:alice groupId 1  →  count: 1 → 2  ✓  (correct)

With get-then-set (not atomic):
  Thread A: GET  →  0
  Thread B: GET  →  0          ← both read 0 before either writes
  Thread A: SET  →  1
  Thread B: SET  →  1          ✗  (one increment lost)
```

Kafka consumers run concurrently across partitions. `HINCRBY` guarantees correctness under concurrency without any locking.

---

## Where Counts Are Incremented

In the **Kafka consumer** (`MessageKafkaConsumer`), after the message is persisted to the DB:

```
message saved to DB
      │
      ▼
load all group members  (one DB query with JOIN FETCH)
      │
      ├── for each member where memberId ≠ senderId
      │       HINCRBY unread:{memberId} {groupId} 1
      │
      └── sender is skipped — no unread for your own messages
```

**Why in the Kafka consumer and not the hot path?**

The hot path (WebSocket send) is user-facing — every millisecond counts.
Incrementing unread counts is a background concern. It belongs in the cold path alongside DB persistence and cache warming.

The user sees the unread count when they open the app, not while the message is in flight. Delaying the increment by ~200ms (Kafka round-trip) is invisible to the user.

---

## Where Counts Are Read

When the user loads their group list (`GET /api/v1/groups`):

```
1. Load user's group memberships from DB  (one query)
2. HGETALL unread:{userId}                (one Redis call — all groups at once)
3. For each group: unreadCount = map.getOrDefault(groupId, 0)
4. Return GroupResponse with unreadCount field
```

Two queries total (1 DB + 1 Redis) regardless of how many groups the user is in.

---

## Where Counts Are Reset

```
POST /api/v1/groups/{groupId}/read

Server:
  1. Verify the user is a group member
  2. HDEL unread:{userId} {groupId}   ← removes the field entirely
  3. Return 204 No Content
```

**Why explicit `POST /read` instead of resetting on `GET /messages`?**

Side effects on GET requests are unexpected and hard to test. A client might prefetch messages in the background without the user actually reading them. An explicit `POST /read` makes the "user read this" action intentional and predictable.

**Why `HDEL` instead of `HSET ... 0`?**

`HDEL` removes the field entirely. This keeps the hash lean — zero-count groups don't occupy space. `HGETALL` then only returns groups with actual unread messages, and `getOrDefault(groupId, 0)` handles the missing field correctly.

---

## No TTL

Unread counts have **no expiry**. If a user doesn't open a group for 30 days, their count should still be there when they return.

This is different from presence (30s TTL) and typing state (5s TTL) — those are ephemeral signals. Unread counts are persistent state that must survive indefinitely until explicitly reset.

---

## Sender Never Gets an Unread Count

```
Alice sends a message
      │
      ├── Kafka consumer increments for Bob   ✓
      ├── Kafka consumer increments for Carol ✓
      └── Kafka consumer SKIPS Alice          ← filtered by: memberId ≠ senderId
```

You never have unread messages from yourself. Incrementing the sender's count would require them to call `POST /read` after every message they send, which is unnecessary noise.

---

## Component Map

```
MessageKafkaConsumer
  consume()
    → messageRepository.save()         ← persist to DB
    → messageCacheService.cacheMessage()  ← warm Redis message cache
    → for each non-sender member:
        unreadService.increment()       ← HINCRBY

UnreadService
  increment(userId, groupId)   → HINCRBY unread:{userId} {groupId} 1
  reset(userId, groupId)       → HDEL    unread:{userId} {groupId}
  getCount(userId, groupId)    → HGET    unread:{userId} {groupId}
  getAllCounts(userId)          → HGETALL unread:{userId}

GroupService
  getMyGroups()
    → groupMemberRepository.findByUser()   ← one DB query
    → unreadService.getAllCounts()          ← one Redis call
    → build GroupResponse with unreadCount

GroupController
  POST /{id}/read
    → groupService.markAsRead()
        → verify membership
        → unreadService.reset()
```

---

## Full Data Flow Diagram

```
                    HOT PATH (user-facing, ~10ms)
Alice types ──► WebSocket SEND ──► MessageService
                                        │
                                        ├── save to outbox (PENDING)
                                        └── STOMP broadcast to group

                    COLD PATH (async, ~200ms later)
OutboxRelay ──► Kafka publish ──► MessageKafkaConsumer
                                        │
                                        ├── INSERT into messages table
                                        ├── Redis sorted set (message cache)
                                        └── HINCRBY for each non-sender member
                                                │
                                                ▼
                                        unread:{bobId}  →  { groupId: 3 }
                                        unread:{carolId} → { groupId: 3 }


                    READ PATH (when Bob opens the app)
Bob ──► GET /api/v1/groups ──► GroupService
                                    │
                                    ├── DB: load memberships
                                    ├── Redis: HGETALL unread:bobId
                                    └── GroupResponse: { unreadCount: 3 }

Bob opens group ──► POST /groups/{id}/read ──► UnreadService.reset()
                                                    │
                                                    └── HDEL unread:bobId groupId
                                                        unreadCount = 0
```
