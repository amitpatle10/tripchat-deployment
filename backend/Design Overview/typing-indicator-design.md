# Typing Indicator Design

> "Alice is typing..." — showing real-time typing state to group members.

---

## The Problem

When Alice is typing a message, Bob should see "Alice is typing..." in the group chat.
When Alice stops typing (or sends the message), the indicator should disappear.

Edge case: what if Alice's browser crashes while she's typing? The indicator should
not stay on screen forever.

---

## How It Works — Simple View

```
Alice starts typing
      │
      ├──► STOMP SEND /app/groups/{id}/typing  { typing: true }
      │
      │    Server:
      │      SET typing:{groupId}:{aliceId} = "1"  TTL=5s     (dead man's switch)
      │      BROADCAST to /topic/groups/{id}/typing
      │              { userId, username, typing: true }
      │
      │                     Bob (subscribed to topic):
      │                       shows "Alice is typing..."
      │
      │  (every 3 seconds while still typing)
      ├──► refresh heartbeat ──► TTL reset to 5s
      │
      │
      Alice sends the message / clears input
      │
      ├──► STOMP SEND /app/groups/{id}/typing  { typing: false }
      │
      │    Server:
      │      DEL typing:{groupId}:{aliceId}
      │      BROADCAST to /topic/groups/{id}/typing
      │              { userId, username, typing: false }
      │
      │                     Bob:
      │                       "Alice is typing..." disappears
```

---

## The Crash Safety Net

The client sends a refresh every 3 seconds while typing. The Redis key has a 5s TTL.

```
Alice typing:

  t=0s   typing=true  ──► SET key TTL=5s
  t=3s   refresh      ──► SET key TTL=5s  (resets)
  t=6s   refresh      ──► SET key TTL=5s  (resets)

  [Alice's browser crashes at t=7s — no more refreshes]

  t=12s  Redis TTL expires  ──► key gone  (indicator disappears from Bob's screen)
```

Without Redis, Alice's crash would leave "Alice is typing..." on Bob's screen forever.
The 5s TTL limits that to at most 5 seconds.

---

## Why Redis? (vs Pure Relay)

Two options:

**Option A — Pure relay (no state)**
Server just forwards the frame. No storage. Simplest.

- Problem: if Alice crashes mid-typing, nobody sends `typing=false`. Bob sees the indicator forever.

**Option B — Redis with TTL**
Server stores state with a 5s TTL. Client refreshes every 3s.

- The TTL handles the crash case automatically. Small cost — 1 Redis write every 3s per active typer.

At 1000 DAU with 10% typing at once (~100 users):

```
100 users × 1 write per 3s = ~33 Redis writes/second
```

Negligible. We go with Option B.

---

## Redis Data Structure

```
Key:    typing:{groupId}:{userId}
Value:  "1"
TTL:    5 seconds

Example — Alice typing in group "Trip to Goa":
  typing:a2159ae9-...:00f584ef-...  →  "1"  (expires in 3s)

Example — Alice and Bob both typing:
  typing:a2159ae9-...:00f584ef-...  →  "1"  (Alice)
  typing:a2159ae9-...:5e695dc2-...  →  "1"  (Bob)
```

**Group + user in the key** because multiple users can type in the same group simultaneously.

---

## What Bob Receives (Broadcast Payload)

```json
{
  "userId": "00f584ef-bc09-4ef3-a587-b542de8c57ec",
  "username": "alice_test",
  "displayName": "Alice",
  "typing": true
}
```

Bob's client uses `userId` to update its local "who is typing" list:

- `typing: true` → add Alice to the list → show "Alice is typing..."
- `typing: false` → remove Alice from the list → indicator disappears

The `userId` field also lets each client suppress its own events — no need to show "You are typing" to yourself.

---

## Separate Topic from Messages

Typing events go to `/topic/groups/{id}/typing`, not `/topic/groups/{id}`.

Why separate?

- Client can handle them differently — typing events are never saved to history, never shown in the message list
- Clients that only care about messages don't receive typing noise
- Easier to filter in unit tests

```
/topic/groups/{id}         ← chat messages (persistent, shown in history)
/topic/groups/{id}/typing  ← ephemeral signals (not persisted, UI only)
```

---

## Client Contract

What the client must do:

| Event                             | Client Action                                     |
| --------------------------------- | ------------------------------------------------- |
| User starts typing                | Send `{ typing: true }`, start a 3s refresh timer |
| User still typing (3s passed)     | Re-send `{ typing: true }` (resets server TTL)    |
| User stops typing (input idle 2s) | Cancel timer, send `{ typing: false }`            |
| User sends the message            | Cancel timer, send `{ typing: false }`            |
| User clears input                 | Cancel timer, send `{ typing: false }`            |

The 2s idle timeout before sending `false` is a UX choice — prevents the indicator from flickering on/off between keystrokes.

---

## Component Map

```
TypingController (STOMP)     → handles /app/groups/{groupId}/typing
  typing()                   → typingService.handleTyping(email, groupId, typing)

TypingService
  handleTyping()
    → validate membership
    → if typing=true:  SET key "1" TTL=5s
    → if typing=false: DEL key
    → broadcast TypingEvent to /topic/groups/{groupId}/typing
```

---

## No Persistence

Typing indicators are **never written to the database**.

They are ephemeral signals — they exist only in Redis (briefly) and in the WebSocket broadcast. Once the frame is delivered, it's gone. No Kafka, no outbox, no messages table.

This is intentional: if Bob is offline when Alice types, he should NOT see "Alice was typing 10 minutes ago" when he comes back online. The indicator is only meaningful in real time.
