# Online Presence Design

> Who is currently online in a group?

---

## The Problem

When Alice opens the "Trip to Goa" group, she wants to see a green dot next to members who are online right now.

The challenge: WebSocket connections can die for two reasons:
- **Clean close** — user closes the tab or logs out (we know about it immediately)
- **Network drop / crash** — connection silently dies (we have no idea unless we track heartbeats)

We need to handle both.

---

## How It Works — Simple View

```
Alice opens app
      │
      ▼
WebSocket connects  ──────────────────────────────► Redis: SET presence:alice TTL=30s
      │
      │  (every 20 seconds)
      ├──────── heartbeat ──────────────────────────► Redis: EXPIRE presence:alice TTL=30s
      │
      │  Alice opens a group
      ├──────── REST GET /groups/{id}/presence ─────► Check Redis for each member
      │                                               Return list of online users
      │
      │  Alice closes tab
      └──────── SessionDisconnectEvent ────────────► Redis: DEL presence:alice
```

---

## The Heartbeat Trick (Dead Man's Switch)

Think of the TTL like a timer that resets every time the client sends a heartbeat.

```
Timeline:

 Alice connected
      │
      ├── heartbeat ──► TTL reset to 30s
      │
      │        20s later
      ├── heartbeat ──► TTL reset to 30s
      │
      │        20s later
      ├── heartbeat ──► TTL reset to 30s
      │
      │   [Alice's WiFi drops — no more heartbeats]
      │
      │        30s of silence
      └──────────────────────────────────────────── Redis key expires → Alice = offline
```

- **30s TTL** — how long until we consider someone offline with no heartbeat
- **20s heartbeat interval** — gives a 10s buffer (one missed heartbeat is fine, two in a row = offline)

Without this, a crashed browser would show the user as "online" forever.

---

## Redis Data Structure

**Why a String key per user (not a Set of all online users)?**

We need individual TTLs per user. Redis TTL works on whole keys, not Set members.

```
Key:    presence:{userId}
Value:  "1"              ← we only care if the key EXISTS, not the value
TTL:    30 seconds

Examples:
  presence:00f584ef-bc09-4ef3-a587-b542de8c57ec  →  "1"  (Alice, expires in 28s)
  presence:5e695dc2-ebda-4842-9216-ef83b6284cf4  →  "1"  (Bob, expires in 14s)
```

Checking if a user is online:
```
EXISTS presence:{userId}   →  1 = online,  0 = offline
```

---

## Two Disconnect Scenarios

| Scenario | Detection | Response Time |
|---|---|---|
| Tab closed / logout | `SessionDisconnectEvent` fires | Immediate |
| WiFi drops / app crash | Redis TTL expires | Up to 30s |

Both are handled. `SessionDisconnectEvent` is the fast path — it fires the moment Spring detects the WebSocket closed cleanly. The TTL is the fallback safety net for silent drops.

---

## Querying Group Presence

When a user opens a group:

```
REST: GET /api/v1/groups/{groupId}/presence

Server does:
  1. Load all group members from DB (one query with JOIN FETCH)
  2. For each member, check EXISTS presence:{userId} in Redis
  3. Return members whose key exists

Response:
[
  { "userId": "...", "username": "alice_test", "displayName": "Alice" },
  { "userId": "...", "username": "bob_test",   "displayName": "Bob"   }
]
```

For a group of 1000 members, this is 1000 `EXISTS` calls pipelined to Redis — still under 1ms.

---

## Why REST and Not WebSocket Push?

We chose **REST on demand** over **broadcasting presence changes in real time**.

**The alternative (broadcast on connect):**
```
Alice connects → server broadcasts to ALL her groups → "Alice is online"
Alice disconnects → server broadcasts to ALL her groups → "Alice is offline"
```

This means: every connect/disconnect triggers N broadcasts (one per group Alice is in).
If Alice is in 10 groups and 100 users are in each group, that's 1000 WebSocket pushes on every connect event.

**Our approach (REST on open):**
- Client fetches presence once when the group is opened
- No broadcasts at all
- At 1000 DAU scale, this is simpler and sufficient

---

## Component Map

```
JwtChannelInterceptor        → authenticates STOMP CONNECT
      │
      ▼
SessionEventListener         → listens to Spring WebSocket events
  onConnected()              → presenceService.markOnline(userId)
  onDisconnected()           → presenceService.markOffline(userId)

PresenceController (STOMP)   → handles /app/presence/heartbeat
  heartbeat()                → presenceService.refreshPresence(userId)

PresenceRestController       → GET /api/v1/groups/{groupId}/presence
  getOnlineMembers()         → presenceService.getOnlineMembers(email, groupId)

PresenceService              → all Redis reads/writes for online state
```

---

## Access Control

Same rule as message history: **non-members get 404, not 403**.

Returning 403 would confirm the group exists. By returning 404 for both "group not found" and "not a member", we don't reveal any information to unauthorized users.
