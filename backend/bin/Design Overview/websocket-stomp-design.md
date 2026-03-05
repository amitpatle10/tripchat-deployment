# WebSocket + STOMP Design — TripChat Messaging

## Why Not HTTP for Chat?

HTTP is request-response — client asks, server answers, connection closes. For chat this means the client has to keep asking "any new messages?" (polling). At 1000 DAUs polling every second = 1000 HTTP requests/second just to check. Wasteful and not real-time.

Three alternatives:

|                | Long Polling         | SSE                  | WebSocket              |
| -------------- | -------------------- | -------------------- | ---------------------- |
| **Direction**  | Server → Client only | Server → Client only | Both ways              |
| **Connection** | New per message      | Persistent one-way   | Persistent full-duplex |
| **Overhead**   | High                 | Low                  | Lowest                 |
| **Best for**   | Simple notifications | Live feeds           | Chat, gaming           |

WebSocket wins — both client and server need to send freely over one persistent connection.

---

## What WebSocket Is

WebSocket starts as an HTTP request and upgrades the connection:

```
Client → Server:
GET /ws HTTP/1.1
Upgrade: websocket

Server → Client:
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
```

After this handshake, HTTP is gone. The same TCP connection becomes a persistent bidirectional pipe — no headers on every message, stays open until explicitly closed.

---

## Why STOMP on Top of WebSocket

Raw WebSocket is just a pipe — bytes in, bytes out. No concept of:

- Where is this message going?
- Who subscribed to what?
- What type of message is this?

You'd invent all of this yourself. STOMP adds structure:

```
SEND
destination:/app/groups/abc-123/messages
content-type:application/json

{"content":"Hello!","clientId":"uuid"}
```

Every STOMP frame has a command, headers (destination, auth token), and body. Spring handles all routing — you just annotate methods with `@MessageMapping`.

---

## Full Connection Flow

```
Step 1 — WebSocket Handshake
Client connects to ws://localhost:8080/ws
HTTP upgrade → persistent WebSocket connection established

Step 2 — STOMP CONNECT (auth here)
Client sends:
    CONNECT
    Authorization: Bearer eyJhbGc...

Server validates JWT → sets security context for this session
Server replies: CONNECTED

Step 3 — Subscribe to group
Client sends:
    SUBSCRIBE
    destination:/topic/groups/{groupId}

Server registers: "this session wants messages for this group"

Step 4 — Send a message
Client sends:
    SEND
    destination:/app/groups/{groupId}/messages

    {"content":"Hello!","clientId":"uuid-123"}

Step 5 — Server processes + broadcasts
@MessageMapping receives it
→ writes to outbox table
→ calls convertAndSend("/topic/groups/{groupId}", response)
→ Spring pushes to ALL sessions subscribed to that destination

Step 6 — All online group members receive instantly
    MESSAGE
    destination:/topic/groups/{groupId}

    {"messageId":"...","content":"Hello!","senderName":"amit"}
```

---

## Authentication — Why Not the Handshake?

Browsers **do not allow custom headers on the WebSocket handshake** — it's a browser security restriction. So we can't send `Authorization: Bearer token` at connection time.

Two options:

|                | Query param                                       | STOMP CONNECT header                         |
| -------------- | ------------------------------------------------- | -------------------------------------------- |
| **How**        | `ws://host/ws?token=eyJ...`                       | Token in STOMP CONNECT frame after handshake |
| **Risk**       | Token in server logs, browser history, proxy logs | Token never in URL                           |
| **Our choice** | ✗                                                 | ✓                                            |

WebSocket connection opens unauthenticated. Client immediately sends STOMP CONNECT with JWT. A Spring `ChannelInterceptor` validates the token and sets the security context. Invalid token → connection rejected.

---

## The STOMP Broker — What It Is and What It Does

The STOMP broker lives inside the Spring Boot app. Its only job:

- Maintain a subscription registry: who is subscribed to what destination
- When `convertAndSend("/topic/groups/abc")` is called, look up the registry and push to matching sessions

```
Client A subscribes to /topic/groups/abc  ─┐
Client B subscribes to /topic/groups/abc  ─┤─ Broker registry
Client C subscribes to /topic/groups/xyz  ─┘

convertAndSend("/topic/groups/abc", message)
    → Broker looks up: Session A, Session B
    → Pushes to A and B only. C gets nothing.
```

**Channel vs Broker:**

- Channel = the pipe that carries a message (just transport)
- Broker = the registry that knows who gets what (dispatcher)

---

## Kafka Broker vs STOMP Broker — Completely Different

This is a common confusion point:

|                          | Kafka Broker                                     | STOMP Broker                              |
| ------------------------ | ------------------------------------------------ | ----------------------------------------- |
| **Lives**                | Separate Kafka server                            | Inside Spring Boot app                    |
| **Clients**              | Your app servers (producers/consumers)           | Browsers (WebSocket)                      |
| **Knows about**          | Topics, partitions, offsets                      | WebSocket sessions, subscriptions         |
| **Job**                  | Durable event streaming between backend services | Push messages to right WebSocket sessions |
| **Browser talks to it?** | Never                                            | Yes (via WebSocket)                       |

```
Browser ──WebSocket──► Spring App ──Kafka Protocol──► Kafka Broker
           (STOMP)          │
                      STOMP Broker
                      (inside Spring)
```

---

## Multi-Server Problem + Why Kafka Doesn't Solve It

When running 3 servers, each has its own in-memory STOMP broker with its own session registry:

```
User A connected to Server 1
User B connected to Server 2

Server 1 receives message → broadcasts to its own sessions → User A gets it ✓
                          → User B is on Server 2, Server 1 has no idea ✗
```

**Why not Kafka for this?**

Kafka consumer groups assign one partition to one server. To make all servers consume all messages you'd need separate consumer groups per server — every message processed N times. Wasteful, and not what Kafka is designed for.

**Right tool: Redis Pub/Sub**

Redis has a built-in ephemeral pub/sub mechanism — in-memory, sub-millisecond, no storage overhead.

```
Server 1 receives message
    ├── broadcasts to its local STOMP sessions (User A)
    └── publishes to Redis channel: group:messages:{groupId}
            ├── Server 2 receives → broadcasts to its local sessions (User B) ✓
            └── Server 3 receives → broadcasts to its local sessions ✓
```

All servers subscribe to Redis channels at startup. Any server publishes, all servers receive and push to their local WebSocket sessions.

---

## Three Tools, Three Responsibilities

```
Kafka        = message reliably gets into the database (durability, persistence)
Redis Pub/Sub = message reaches all online users across all servers (cross-server broadcast)
STOMP Broker  = message reaches the right WebSocket sessions on this server (local routing)
```

None of them replace each other.

---

## Decisions

| Decision              | Choice                        | Why                                                                       |
| --------------------- | ----------------------------- | ------------------------------------------------------------------------- |
| Protocol              | STOMP over WebSocket          | Destinations + subscriptions without inventing our own protocol           |
| Auth                  | STOMP CONNECT header with JWT | Token never in URL or server logs                                         |
| Broker type           | In-memory SimpleBroker        | Single server for now — zero config                                       |
| Cross-server (future) | Redis Pub/Sub                 | Already in stack, sub-ms latency, right tool for ephemeral fan-out        |
| SockJS                | Yes                           | Fallback for corporate firewalls that block WebSocket                     |
| App prefix            | `/app`                        | Client sends to `/app/groups/{id}/messages` → routed to `@MessageMapping` |
| Topic prefix          | `/topic`                      | Client subscribes to `/topic/groups/{id}` → receives broadcasts           |
| Inbound thread        | Default (1 thread)            | Handler is fast — just outbox write, heavy work is async                  |
