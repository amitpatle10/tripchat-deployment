# TripChat – System Design

## Overview

TripChat is a real-time group chat system built for travel groups. It uses a **Transactional Outbox + Kafka** pipeline to guarantee message durability while delivering messages to online users with sub-100ms latency via STOMP WebSocket.

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT (Browser)                           │
│                                                                     │
│   ┌─────────────┐   STOMP /app/groups/{id}/messages                │
│   │  Send Msg   │ ──────────────────────────────────►              │
│   └─────────────┘                                                   │
│                                                                     │
│   ┌─────────────┐   Subscribe /topic/groups/{id}                   │
│   │  Receive    │ ◄──────────────────────────────────              │
│   └─────────────┘                                                   │
│                                                                     │
│   ┌─────────────┐   GET /api/v1/groups/{id}/messages               │
│   │  Catch-up   │ ──────────────────────────────────►              │
│   └─────────────┘   (on reconnect / first load)                    │
└─────────────────────────────────────────────────────────────────────┘
         │                    │                          │
         │ WebSocket          │ WebSocket                │ REST
         ▼                    ▼                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     SPRING BOOT APPLICATION                         │
│                                                                     │
│  ┌──────────────────────┐      ┌──────────────────────────────┐    │
│  │  MessageController   │      │    MessageRestController     │    │
│  │  (STOMP handler)     │      │    GET  /messages            │    │
│  │  @MessageMapping     │      │    DELETE /messages/{id}     │    │
│  └──────────┬───────────┘      └──────────────┬───────────────┘    │
│             │                                  │                    │
│             ▼                                  ▼                    │
│  ┌──────────────────────┐      ┌──────────────────────────────┐    │
│  │   MessageService     │      │     MessageQueryService      │    │
│  │   (Hot Path ~10ms)   │      │     (Cache-aside + lock)     │    │
│  └──────────┬───────────┘      └──────────────┬───────────────┘    │
│             │                                  │                    │
│    ┌────────┴────────┐                ┌────────┴────────┐          │
│    │  Write Outbox   │                │   Redis Cache   │          │
│    │  (PENDING)      │                │  (sorted set)   │          │
│    └────────┬────────┘                └────────┬────────┘          │
│             │ broadcast optimistic              │ miss              │
│             │ (id=null, clientId only)          ▼                   │
│             │                         ┌──────────────────┐         │
│             │                         │  Message Table   │         │
│             │                         │  (PostgreSQL)    │         │
│             │                         └──────────────────┘         │
│             │                                                       │
│  ┌──────────▼───────────┐                                          │
│  │    OutboxRelay       │                                          │
│  │  (polls every 100ms) │                                          │
│  └──────────┬───────────┘                                          │
│             │ publish ChatMessageEvent                              │
│             │ partitioned by groupId                               │
└─────────────┼───────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────┐
│   KAFKA  (chat.messages)    │
│   3 partitions, 3 replicas  │
│   key = groupId             │
│   (per-group ordering)      │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  MessageKafkaConsumer (Cold Path)                   │
│                                                                     │
│   1. Idempotency check (clientId UNIQUE constraint)                │
│   2. Persist to messages table (PostgreSQL)                        │
│   3. Write-through to Redis sorted set                             │
│   4. Broadcast confirmed msg via STOMP (real DB UUID now set)      │
│   5. Increment unread counts for all members except sender         │
│   6. Manual-commit Kafka offset (ONLY after DB write succeeds)     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Message Flow

### 1. Sending a Message (Hot Path)

```
Client
  │
  │  STOMP SEND /app/groups/{groupId}/messages
  │  { clientId: "uuid-gen-by-client", content: "Hello!" }
  │
  ▼
JwtChannelInterceptor
  │  validates JWT on CONNECT frame
  │  attaches Principal to session
  ▼
MessageController.handleMessage()
  │
  ▼
MessageService.sendMessage()
  │
  ├─► [Guard] outboxRepository.existsByClientId(clientId)
  │         if true → return early (idempotent, already processed)
  │
  ├─► Write to outbox table
  │     status  = PENDING
  │     groupId = FK
  │     senderId
  │     content
  │     clientId (UNIQUE)
  │     createdAt
  │
  ├─► Build optimistic MessageResponse
  │     id      = null  (not persisted yet)
  │     clientId = client-provided UUID
  │     content, sender, groupId, createdAt
  │
  ├─► SimpMessagingTemplate.convertAndSend(
  │       "/topic/groups/{groupId}", optimisticResponse)
  │       → delivered instantly to all online subscribers
  │
  └─► Return @SendToUser("/queue/confirmation") to sender
```

### 2. Outbox Relay → Kafka (Background, every 100ms)

```
OutboxRelay (@Scheduled, fixedDelay=100ms)
  │
  ├─► outboxRepository.findPendingRecords(page: 0, size: 100)
  │     ORDER BY created_at ASC  (FIFO per group)
  │     WHERE status = PENDING
  │
  ├─► For each outbox record:
  │     ├─► kafkaTemplate.send(
  │     │       topic     = "chat.messages",
  │     │       key       = groupId.toString(),   ← partition key
  │     │       value     = ChatMessageEvent{...}
  │     │   )
  │     │
  │     ├─► On SUCCESS:
  │     │     outbox.status     = PUBLISHED
  │     │     outbox.publishedAt = now()
  │     │
  │     └─► On FAILURE:
  │           outbox.retryCount++
  │           if retryCount >= 3 → status = FAILED
  │
  └─► [Nightly 2am] deletePublishedBefore(now - 7 days)
```

**Why partition by groupId?**
All messages for the same group land on the same partition → Kafka guarantees ordering within a partition → messages consumed in send order per group.

### 3. Kafka Consumer → Persistence (Cold Path)

```
MessageKafkaConsumer
  │  @KafkaListener(topics = "chat.messages")
  │  AckMode = MANUAL_IMMEDIATE
  │
  ├─► [Idempotency] messageRepository.existsByClientId(clientId)
  │         if true → ack offset, skip insert (duplicate delivery)
  │
  ├─► Load Group  (DB lookup by groupId)
  ├─► Load Sender (JOIN FETCH to prevent N+1)
  │
  ├─► messageRepository.save(Message{
  │       id       = auto UUID
  │       group    = loaded group
  │       sender   = loaded sender
  │       content
  │       clientId (UNIQUE)
  │       createdAt
  │   })
  │
  ├─► messageCacheService.cacheMessage(groupId, message)
  │     ZADD group:messages:{groupId} <epoch_ms> <serialized>
  │     ZREMRANGEBYRANK ...  (keep only latest 50)
  │     EXPIRE 24h
  │
  ├─► SimpMessagingTemplate.convertAndSend(
  │       "/topic/groups/{groupId}", confirmedResponse)
  │       → now carries real DB-assigned UUID
  │       → client replaces optimistic entry (matched by clientId)
  │
  ├─► unreadService.increment(groupId, memberIds except sender)
  │     HINCRBY unread:{userId} {groupId} 1
  │
  └─► acknowledgment.acknowledge()   ← offset committed LAST
            (crash before this → Kafka re-delivers, idempotency handles it)
```

### 4. Offline User Catch-up (REST)

```
Client reconnects / opens group chat
  │
  │  GET /api/v1/groups/{groupId}/messages
  │      ?cursorTime=<epoch_ms>&cursorId=<uuid>   (optional, for pagination)
  ▼
MessageRestController → MessageQueryService
  │
  ├─► [Auth] validate JWT, check group membership
  │
  ├─► [Cache Check] messageCacheService.getLatest(groupId)
  │       ZREVRANGE group:messages:{groupId} 0 49
  │       if HIT → return cached messages
  │
  ├─► [Cache Miss] Acquire distributed lock
  │       SET NX group:messages:{groupId}:lock TTL=5s
  │       only first thread proceeds; others wait 50ms & retry cache
  │       (prevents cache stampede)
  │
  ├─► messageRepository.findLatestByGroup(groupId, limit=50)
  │       JOIN FETCH sender
  │       ORDER BY (created_at DESC, id DESC)
  │       cursor: WHERE (created_at, id) < (cursorTime, cursorId)
  │
  ├─► messageCacheService.populate(groupId, messages)  ← warm cache
  │
  └─► return List<MessageResponse>   (empty list = end of history)

Pagination:
  Page 1: GET /messages              → returns latest 50
  Page 2: GET /messages?cursorTime=X&cursorId=Y  → 50 older than cursor
  Page N: ... until empty list
```

---

## Presence & Unread Counts

### Online/Offline Tracking

```
STOMP CONNECT
  └─► SessionEventListener.handleConnect()
        PresenceService.markOnline(userId)
        SET presence:{userId} = 1  TTL=90s  (refreshed on activity)

STOMP DISCONNECT (or TCP drop)
  └─► SessionEventListener.handleDisconnect()
        PresenceService.markOffline(userId)
        DEL presence:{userId}
```

### Unread Count Flow

```
Kafka Consumer persists message
  └─► UnreadService.increment(groupId, [memberId1, memberId2, ...])
        HINCRBY unread:{memberId1} {groupId} 1
        HINCRBY unread:{memberId2} {groupId} 1
        ...

User opens group chat (marks as read)
  └─► UnreadService.reset(userId, groupId)
        HSET unread:{userId} {groupId} 0

User loads groups list
  └─► GroupService.getMyGroups(userId)
        HGETALL unread:{userId}
        → attaches unread count to each group in one Redis round-trip
```

---

## Message Deletion

```
Client
  │  DELETE /api/v1/groups/{groupId}/messages/{messageId}
  ▼
MessageDeleteService
  ├─► [Auth] only sender can delete their own message
  ├─► messageRepository.findByIdAndGroup_Id(messageId, groupId)
  ├─► message.deletedAt = now()   ← soft delete, content preserved in DB
  ├─► messageCacheService.evictGroup(groupId)  ← evict entire group cache key
  └─► SimpMessagingTemplate.convertAndSend(
          "/topic/groups/{groupId}",
          MessageResponse{ deleted=true, content=null })
      → online clients replace entry with "This message was deleted"
```

---

## Data Model

### outbox table

```
┌─────────────────────────────────────────────────────────────┐
│  outbox                                                     │
├───────────────┬────────────────────────────────────────────┤
│  id           │ UUID (PK)                                  │
│  group_id     │ FK → groups.id  (CASCADE DELETE)           │
│  sender_id    │ UUID                                       │
│  content      │ TEXT                                       │
│  client_id    │ VARCHAR(36) UNIQUE  ← idempotency key      │
│  status       │ ENUM(PENDING, PUBLISHED, FAILED)           │
│  retry_count  │ INT DEFAULT 0                              │
│  created_at   │ TIMESTAMP                                  │
│  published_at │ TIMESTAMP (nullable)                       │
├───────────────┴────────────────────────────────────────────┤
│  INDEX (status, created_at)  ← relay polling               │
└─────────────────────────────────────────────────────────────┘
```

### messages table

```
┌─────────────────────────────────────────────────────────────┐
│  messages                                                   │
├───────────────┬────────────────────────────────────────────┤
│  id           │ UUID (PK)                                  │
│  group_id     │ FK → groups.id  (CASCADE DELETE)           │
│  sender_id    │ FK → users.id   (SET NULL on delete)       │
│  content      │ TEXT                                       │
│  client_id    │ VARCHAR(36) UNIQUE  ← dedup after Kafka    │
│  created_at   │ TIMESTAMP                                  │
│  deleted_at   │ TIMESTAMP (nullable) ← soft delete         │
├───────────────┴────────────────────────────────────────────┤
│  INDEX (group_id, created_at DESC)  ← history queries      │
└─────────────────────────────────────────────────────────────┘
```

---

## Redis Key Schema

```
Key                                Type         TTL       Purpose
─────────────────────────────────────────────────────────────────────────
group:messages:{groupId}           Sorted Set   24h       Latest 50 msgs
  score = epoch_ms, member = JSON

group:messages:{groupId}:lock      String       5s        Stampede lock

presence:{userId}                  String       90s       Online indicator

unread:{userId}                    Hash         none      { groupId → count }
```

---

## Kafka Configuration

```
Topic:          chat.messages
Partitions:     3
Replication:    3  (MSK Serverless in production)
Partition key:  groupId  (per-group message ordering)
Consumer group: tripchat-group
Ack mode:       MANUAL_IMMEDIATE
Auth:           SASL/SSL + IAM (production), plaintext (dev)
```

---

## Reliability Guarantees

| Scenario | Behavior |
|---|---|
| App crashes after outbox write, before Kafka publish | OutboxRelay re-publishes on restart (PENDING record survives) |
| App crashes after Kafka publish, before PUBLISHED mark | OutboxRelay re-publishes; Kafka consumer dedupes via `clientId` |
| App crashes after DB write, before Kafka offset commit | Kafka re-delivers; consumer skips insert (clientId exists), re-broadcasts STOMP |
| Client sends duplicate (network retry) | `outboxRepository.existsByClientId()` guard returns early |
| Redis cache down | Falls through to DB; non-fatal |
| Multiple consumers race on same message | DB UNIQUE constraint on `client_id` — only one INSERT succeeds |

---

## Component Summary

| Component | Role |
|---|---|
| `MessageController` | STOMP entrypoint, routes to MessageService |
| `MessageService` | Hot path: writes outbox, optimistic broadcast |
| `OutboxRelay` | Polls outbox every 100ms, publishes to Kafka |
| `MessageKafkaConsumer` | Cold path: persists, caches, broadcasts confirmed msg |
| `MessageRestController` | REST API for history and deletion |
| `MessageQueryService` | Cache-aside read with stampede prevention |
| `MessageCacheService` | Redis sorted set read/write operations |
| `MessageDeleteService` | Soft delete + STOMP broadcast |
| `UnreadService` | Redis Hash unread counter per user per group |
| `SessionEventListener` | Tracks WebSocket connect/disconnect for presence |
| `JwtChannelInterceptor` | Validates JWT on STOMP CONNECT frame |
