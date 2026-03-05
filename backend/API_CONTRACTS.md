# TripChat — API Contracts

> **Base URL:** `http://localhost:8080`
> **API Version:** `/api/v1`
> **Auth:** All endpoints except `/api/v1/auth/**` require `Authorization: Bearer <token>`

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Groups](#2-groups)
3. [Messages — REST](#3-messages--rest)
4. [WebSocket / STOMP](#4-websocket--stomp)
   - [Connection](#connection)
   - [Send a Message](#send-a-message-stomp)
   - [Receive Messages](#receive-messages-stomp)
   - [Typing Indicators](#typing-indicators-stomp)
   - [Online Presence](#online-presence-stomp)
5. [Presence — REST](#5-presence--rest)
6. [Error Responses](#6-error-responses)

---

## 1. Authentication

### POST `/api/v1/auth/register`

Register a new user. Returns a JWT immediately (no email verification in Phase 1).

**Request**
```json
{
  "email": "alice@example.com",
  "password": "Secret1@",
  "username": "alice_92",
  "displayName": "Alice"
}
```

| Field         | Type   | Rules                                                                   |
|---------------|--------|-------------------------------------------------------------------------|
| `email`       | string | Required · valid email format · max 255 chars                           |
| `password`    | string | Required · min 8 chars · at least 1 digit · at least 1 special char (`@$!%*?&`) |
| `username`    | string | Required · 3–50 chars · letters, digits, underscores only (`[a-zA-Z0-9_]+`) |
| `displayName` | string | Required · 2–30 chars                                                  |

**Response — 201 Created**
```json
{
  "token": "eyJhbGci...",
  "tokenType": "Bearer",
  "expiresIn": 86400000,
  "user": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "email": "alice@example.com",
    "username": "alice_92",
    "displayName": "Alice"
  }
}
```

| Field       | Type   | Notes                              |
|-------------|--------|------------------------------------|
| `token`     | string | JWT — send as `Authorization: Bearer <token>` |
| `tokenType` | string | Always `"Bearer"`                  |
| `expiresIn` | number | Milliseconds until expiry          |
| `user`      | object | See [UserResponse](#userresponse)  |

**Errors**

| Status | When                              |
|--------|-----------------------------------|
| 400    | Validation failure (field errors) |
| 409    | Email or username already taken   |

---

### POST `/api/v1/auth/login`

Authenticate an existing user.

**Request**
```json
{
  "email": "alice@example.com",
  "password": "Secret1@"
}
```

| Field      | Type   | Rules                     |
|------------|--------|---------------------------|
| `email`    | string | Required · valid email    |
| `password` | string | Required · non-blank      |

**Response — 200 OK**

Same shape as [register response](#post-apiv1authregister).

**Errors**

| Status | When                                   |
|--------|----------------------------------------|
| 400    | Blank email or password                |
| 401    | Wrong credentials (generic message — no user enumeration) |

---

## 2. Groups

All endpoints require `Authorization: Bearer <token>`.

---

### POST `/api/v1/groups`

Create a new group. The creator becomes the ADMIN automatically.

**Request**
```json
{
  "name": "Paris Trip 2025",
  "description": "Planning our summer trip to Paris"
}
```

| Field         | Type   | Rules                             |
|---------------|--------|-----------------------------------|
| `name`        | string | Required · 3–50 chars             |
| `description` | string | Optional · max 500 chars          |

**Response — 201 Created**

See [GroupResponse](#groupresponse).

**Errors**

| Status | When              |
|--------|-------------------|
| 400    | Validation failure |

---

### GET `/api/v1/groups`

Get all groups the current user is a member of.

**Response — 200 OK**
```json
[
  { /* GroupResponse */ },
  { /* GroupResponse */ }
]
```

Returns an empty array `[]` if the user is not in any group.

---

### GET `/api/v1/groups/{id}`

Get a single group by ID.

**Path Params**

| Param | Type | Notes         |
|-------|------|---------------|
| `id`  | UUID | Group ID      |

**Response — 200 OK**

See [GroupResponse](#groupresponse).

**Errors**

| Status | When                                      |
|--------|-------------------------------------------|
| 404    | Group not found, or user is not a member  |

---

### POST `/api/v1/groups/join`

Join a group using an invite code.

**Request**
```json
{
  "inviteCode": "AB12CD34"
}
```

| Field        | Type   | Rules                        |
|--------------|--------|------------------------------|
| `inviteCode` | string | Required · exactly 8 chars   |

**Response — 200 OK**

See [GroupResponse](#groupresponse).

**Errors**

| Status | When                              |
|--------|-----------------------------------|
| 400    | Validation failure · group is full (max 1000 members) |
| 404    | Invite code not found             |
| 409    | User is already a member          |

---

### DELETE `/api/v1/groups/{id}/leave`

Leave a group.

**Path Params**

| Param | Type | Notes    |
|-------|------|----------|
| `id`  | UUID | Group ID |

**Response — 204 No Content** (empty body)

**Errors**

| Status | When                              |
|--------|-----------------------------------|
| 400    | Admin cannot leave (must delete the group instead) |
| 404    | Group not found or user is not a member |

---

### POST `/api/v1/groups/{id}/invite/regenerate`

Regenerate the invite code for a group. Admin only.

**Path Params**

| Param | Type | Notes    |
|-------|------|----------|
| `id`  | UUID | Group ID |

**Response — 200 OK**

See [GroupResponse](#groupresponse) with the new `inviteCode`.

**Errors**

| Status | When                         |
|--------|------------------------------|
| 403    | Caller is not the group admin |
| 404    | Group not found or not a member |

---

### POST `/api/v1/groups/{id}/read`

Mark all messages in a group as read. Resets the unread count to 0.
Call this when the user opens (focuses) a group chat.

**Path Params**

| Param | Type | Notes    |
|-------|------|----------|
| `id`  | UUID | Group ID |

**Response — 204 No Content** (empty body)

**Errors**

| Status | When                            |
|--------|---------------------------------|
| 404    | Group not found or not a member |

---

## 3. Messages — REST

### GET `/api/v1/groups/{groupId}/messages`

Load message history with cursor-based pagination (newest first).

**Path Params**

| Param     | Type | Notes    |
|-----------|------|----------|
| `groupId` | UUID | Group ID |

**Query Params**

| Param        | Type             | Required | Notes                                           |
|--------------|------------------|----------|-------------------------------------------------|
| `cursorTime` | ISO-8601 string  | No       | `createdAt` of the oldest message on current page |
| `cursorId`   | UUID             | No       | `id` of the oldest message on current page      |

Both `cursorTime` and `cursorId` must be provided together (composite cursor).
Omit both for the first page.

**Pagination flow**
```
First page:   GET /api/v1/groups/{id}/messages
Next page:    GET /api/v1/groups/{id}/messages?cursorTime=2025-07-01T10:00:00Z&cursorId=<uuid>

Stop when:    response array is empty []
```

**Response — 200 OK**
```json
[
  { /* MessageResponse — newest first */ },
  { /* MessageResponse */ }
]
```

Returns up to 50 messages per page. Empty array signals end of history.

**Errors**

| Status | When                            |
|--------|---------------------------------|
| 404    | Group not found or not a member |

---

## 4. WebSocket / STOMP

### Connection

```
URL:      ws://localhost:8080/ws
Protocol: STOMP over WebSocket (SockJS-compatible)
Auth:     Pass JWT in the STOMP CONNECT frame header:
          connect header → { "Authorization": "Bearer <token>" }
```

**STOMP endpoint prefixes**

| Prefix   | Used for                              |
|----------|---------------------------------------|
| `/app`   | Client → Server (send to handler)     |
| `/topic` | Server → All subscribers (broadcast)  |
| `/user`  | Server → Specific user only           |

---

### Send a Message (STOMP)

**Destination:** `SEND /app/groups/{groupId}/messages`

**Payload**
```json
{
  "clientId": "550e8400-e29b-41d4-a716-446655440000",
  "content": "Hello everyone!"
}
```

| Field      | Type   | Rules                                                               |
|------------|--------|---------------------------------------------------------------------|
| `clientId` | UUID   | Required · generate a fresh `crypto.randomUUID()` per unique message (idempotency key — safe to retry on network failure) |
| `content`  | string | Required · non-blank · max 4000 chars                               |

**Server sends back to sender on** `/user/queue/confirmation`
```json
{
  "id": "...",
  "groupId": "...",
  "senderId": "...",
  "senderUsername": "alice_92",
  "senderDisplayName": "Alice",
  "content": "Hello everyone!",
  "clientId": "550e8400-e29b-41d4-a716-446655440000",
  "createdAt": "2025-07-01T10:00:00.000Z",
  "deleted": false
}
```

**Server broadcasts to all group members on** `/topic/groups/{groupId}/messages`

Same [MessageResponse](#messageresponse) shape.

**Client logic**
- Match incoming broadcast `clientId` to a pending optimistic message to replace it.
- If the broadcast arrives before the confirmation, that's fine — deduplicate by `clientId`.

---

### Receive Messages (STOMP)

Subscribe when opening a group chat.

**Subscribe to:** `/topic/groups/{groupId}/messages`

Receives [MessageResponse](#messageresponse) whenever any member sends a message.

---

### Typing Indicators (STOMP)

**Send (client → server):** `SEND /app/groups/{groupId}/typing`

```json
{ "typing": true }
```

| Field    | Type    | Notes                               |
|----------|---------|-------------------------------------|
| `typing` | boolean | `true` = started typing · `false` = stopped |

**Client rules:**
- Send `true` when user begins typing
- Send `false` when user sends the message or clears the input
- Refresh every **3 seconds** while still typing (keeps the server-side 5s TTL alive)
- The server auto-clears after 5s of no refresh (crash-safe dead man's switch)

**Receive (subscribe):** `/topic/groups/{groupId}/typing`

```json
{
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "username": "bob_45",
  "displayName": "Bob",
  "typing": true
}
```

**Client logic:**
- Suppress events where `userId` equals the current user's ID (don't show "you are typing").
- Show "Bob is typing..." when `typing: true`, hide when `typing: false`.

---

### Online Presence (STOMP)

Send a heartbeat every **20 seconds** to stay online.

**Send:** `SEND /app/presence/heartbeat`
**Payload:** _(empty — no body required)_

The server sets a 30-second TTL in Redis. Two missed heartbeats (40s) = user appears offline.

**Presence updates are not pushed over WebSocket.** Use the [REST endpoint](#5-presence--rest) to get online members when opening a group.

---

## 5. Presence — REST

### GET `/api/v1/groups/{groupId}/presence`

Get the list of group members currently online.
Call once when the user opens a group chat.

**Path Params**

| Param     | Type | Notes    |
|-----------|------|----------|
| `groupId` | UUID | Group ID |

**Response — 200 OK**
```json
[
  {
    "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "username": "bob_45",
    "displayName": "Bob"
  }
]
```

Empty array `[]` = no members online.

**Errors**

| Status | When                            |
|--------|---------------------------------|
| 404    | Group not found or not a member |

---

## 6. Error Responses

All errors follow this shape:

```json
{
  "timestamp": "2025-07-01T10:00:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "details": {
    "email": "Email must be a valid email address",
    "password": "Password must be at least 8 characters..."
  }
}
```

`details` is only present on validation errors (400 from `@Valid`).
All other errors have `timestamp`, `status`, `error`, and `message` only.

**Status code reference**

| Code | Meaning                                              |
|------|------------------------------------------------------|
| 400  | Validation failure, group full, admin cannot leave   |
| 401  | Missing/invalid JWT, wrong login credentials         |
| 403  | Non-admin attempting admin-only action               |
| 404  | Resource not found, invalid invite code, non-member access |
| 409  | Email taken, username taken, already a group member  |
| 500  | Unexpected server error                              |

---

## Shared Response Shapes

### UserResponse

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "email": "alice@example.com",
  "username": "alice_92",
  "displayName": "Alice"
}
```

### GroupResponse

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Paris Trip 2025",
  "description": "Planning our summer trip",
  "inviteCode": "AB12CD34",
  "memberCount": 5,
  "myRole": "ADMIN",
  "createdBy": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "createdAt": "2025-07-01T10:00:00.000Z",
  "unreadCount": 12
}
```

| Field         | Type   | Notes                                  |
|---------------|--------|----------------------------------------|
| `myRole`      | string | `"ADMIN"` or `"MEMBER"`               |
| `inviteCode`  | string | 8-char code — visible to all members   |
| `unreadCount` | number | Messages received since last `/read` call |

### MessageResponse

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "groupId": "...",
  "senderId": "...",
  "senderUsername": "alice_92",
  "senderDisplayName": "Alice",
  "content": "Hello everyone!",
  "clientId": "550e8400-e29b-41d4-a716-446655440000",
  "createdAt": "2025-07-01T10:00:00.000Z",
  "deleted": false
}
```

| Field               | Notes                                                      |
|---------------------|------------------------------------------------------------|
| `senderId`          | `null` if the sender's account was deleted                 |
| `senderUsername`    | `null` if sender deleted — show "Deleted User" in UI       |
| `senderDisplayName` | `null` if sender deleted                                   |
| `deleted`           | `true` = message was deleted — show "This message was deleted" |

---

## Quick Reference

| Method | Endpoint                                    | Auth | Description               |
|--------|---------------------------------------------|------|---------------------------|
| POST   | `/api/v1/auth/register`                     | No   | Register                  |
| POST   | `/api/v1/auth/login`                        | No   | Login                     |
| POST   | `/api/v1/groups`                            | Yes  | Create group              |
| GET    | `/api/v1/groups`                            | Yes  | My groups                 |
| GET    | `/api/v1/groups/{id}`                       | Yes  | Get group                 |
| POST   | `/api/v1/groups/join`                       | Yes  | Join group by invite code |
| DELETE | `/api/v1/groups/{id}/leave`                 | Yes  | Leave group               |
| POST   | `/api/v1/groups/{id}/invite/regenerate`     | Yes  | Regenerate invite code (admin) |
| POST   | `/api/v1/groups/{id}/read`                  | Yes  | Mark group as read        |
| GET    | `/api/v1/groups/{groupId}/messages`         | Yes  | Message history (paginated) |
| GET    | `/api/v1/groups/{groupId}/presence`         | Yes  | Who is online             |
| WS     | `ws://localhost:8080/ws` (STOMP)            | JWT in CONNECT header | WebSocket connection |
| STOMP  | SEND `/app/groups/{groupId}/messages`       | —    | Send a message            |
| STOMP  | SUB `/topic/groups/{groupId}/messages`      | —    | Receive messages          |
| STOMP  | SUB `/user/queue/confirmation`              | —    | Send confirmation         |
| STOMP  | SEND `/app/groups/{groupId}/typing`         | —    | Typing indicator          |
| STOMP  | SUB `/topic/groups/{groupId}/typing`        | —    | Receive typing events     |
| STOMP  | SEND `/app/presence/heartbeat`              | —    | Stay online (every 20s)   |
