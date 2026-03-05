# Miscellaneous Learnings — Implementation Notes

Things learned while building the messaging pipeline (WebSocket + STOMP + Kafka + Redis + JPA).

---

## 1. Spring Security Has Two Separate Auth Layers for WebSocket

Spring Security operates on **HTTP requests**. WebSocket is established via an HTTP upgrade request — this is its own HTTP request that passes through the Spring Security filter chain.

STOMP authentication is a completely separate concern that happens **after** the WebSocket connection is open.

This means:
- The WebSocket handshake endpoint (`/ws/**`) must be `permitAll()` at the HTTP security layer — the filter chain has no knowledge of what's inside the WebSocket frames.
- JWT validation belongs in a STOMP channel interceptor (`ChannelInterceptor`), which runs on the STOMP CONNECT frame, not the HTTP handshake.
- If you require auth at the HTTP layer for the WebSocket endpoint, the connection is rejected before STOMP even gets a chance to run.

```
HTTP Layer  (Spring Security filter chain)   → only sees the upgrade request
STOMP Layer (JwtChannelInterceptor)          → sees STOMP frames after connection
```

Two layers, two places to configure — confusing them causes a 403 before any STOMP frame is ever sent.

---

## 2. SockJS Is a Transport Negotiation Protocol, Not Raw WebSocket

When you enable `.withSockJS()` on a Spring WebSocket endpoint, the server stops speaking raw WebSocket and starts speaking the SockJS protocol. The client must match.

SockJS negotiation flow:
```
Client → GET /ws/info                  ← "what transports do you support?"
Server → { websocket: true, ... }

Client → GET /ws/{server}/{session}/websocket   ← actual connection
Server → 101 Switching Protocols
```

A raw WebSocket client skips the `/info` step and connects directly. The server doesn't understand this and returns 400.

**Practical rule:** if the server has `.withSockJS()`, every client must use a SockJS client library. For Node.js testing, that means installing `sockjs-client` and using `webSocketFactory: () => new SockJS(url)`.

The reason SockJS exists is simple: corporate firewalls and old proxies often block the WebSocket Upgrade header. SockJS falls back to HTTP streaming or long-polling transparently, so the client code never changes.

---

## 3. Spring Data's `save()` Uses `isNew()` to Choose Between INSERT and UPDATE

Spring Data JPA's `SimpleJpaRepository.save()` has a decision:

```java
if (entityInformation.isNew(entity)) {
    em.persist(entity);   // INSERT
} else {
    em.merge(entity);     // tries UPDATE, then fails if row doesn't exist
}
```

For entities with `@GeneratedValue`, `isNew()` returns `true` only when `id == null`. The moment you set a non-null ID on the entity before calling `save()`, Spring Data thinks it's a detached (already-existing) entity and calls `merge()`.

`merge()` does a SELECT to find the row, finds nothing, and throws `StaleObjectStateException`. The transaction is marked rollback-only and the row is never inserted.

**Practical rule:** if your entity uses `@GeneratedValue`, never pre-assign the ID before calling `save()`. Let JPA generate it. Use a separate business key (like `clientId`) for idempotency — that's what UNIQUE constraints are for.

---

## 4. UUID Validation Is Strict — Jackson Enforces Format at Deserialization

A UUID has this exact structure: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` — five groups of 8-4-4-4-12 hex characters.

When Jackson deserializes a JSON string into a `UUID` field, it validates the format strictly. If the string doesn't match — wrong length, non-hex characters, wrong group count — Jackson throws a `JsonMappingException`.

In a STOMP `@MessageMapping` handler, this exception is swallowed silently. The handler is never invoked, no error is returned to the client, no log entry appears at ERROR level. The message disappears.

**Practical rule:** when building UUIDs for test payloads, always use the platform's UUID generator — `crypto.randomUUID()` in Node.js, `UUID.randomUUID()` in Java. Never construct a UUID string by concatenating a prefix with a timestamp or counter, as timestamp digits have different lengths across platforms and time.

---

## 5. STOMP `@SendToUser` Needs `/queue` Registered in the Broker

`@SendToUser("/queue/confirmation")` is the STOMP equivalent of sending a private message to one specific user. Spring rewrites the destination to `/queue/confirmation-{sessionId}` so only that session receives it.

For this to work, two things must be configured in `WebSocketConfig`:
```java
registry.enableSimpleBroker("/topic", "/queue");   // /queue must be here
registry.setUserDestinationPrefix("/user");         // client subscribes to /user/queue/...
```

Without `/queue` in the broker, the broker doesn't recognise the destination and silently drops the frame. Without `setUserDestinationPrefix`, Spring doesn't know what prefix to strip when rewriting user destinations.

There's no error when either is missing — the message just vanishes. Always configure both together when you use `@SendToUser`.

---

## 6. Manual Kafka Offset Commit — Order Matters

With `AckMode.MANUAL_IMMEDIATE`, you control exactly when the offset is committed. The rule is:

```
commit AFTER the work succeeds, never before
```

The correct order:
```
1. Process the message (write to DB, update cache)
2. ack.acknowledge()   ← offset committed here
```

If you commit before writing to DB and the app crashes, Kafka moves the offset forward. When the consumer restarts, it starts from after the committed offset — the message is **lost permanently**.

If you commit after writing to DB and the app crashes before the commit, Kafka re-delivers the message. This causes a duplicate write attempt. The `clientId` UNIQUE constraint handles this gracefully — the duplicate insert fails silently and the consumer acknowledges and moves on.

This is the at-least-once delivery guarantee in practice: occasional duplicates are acceptable and handleable; lost messages are not.

---

## 7. Kafka Consumer Errors Are Silently Retried by Default

Spring Kafka's default error handler (`DefaultErrorHandler`) retries a failed message up to 10 times with no delay before giving up. During all these retries, the consumer is blocked on that one message — no other messages from that partition are processed.

This is why errors in the consumer need to be caught and handled carefully:
- Transient errors (DB connection timeout) → let it retry → eventually succeeds
- Permanent errors (malformed payload, missing FK) → catch and acknowledge → don't block the partition

If you don't catch and acknowledge permanent errors, the consumer exhausts its retries, logs `Backoff exhausted`, and then calls the recovery callback (by default: log and move on). You've now lost the message unless you route it to a DLQ.

Manual acknowledgment + explicit try/catch gives you complete control over this behaviour.
