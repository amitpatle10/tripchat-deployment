# Redis Design — TripChat Messaging

## What Redis Is

Redis is an in-memory data store — everything lives in RAM, which is why reads are sub-millisecond. It's not just a cache; it's a typed data structure server. You operate on structures (lists, sets, sorted sets) atomically, not just store blobs.

Tradeoff: RAM is expensive and finite. Redis is the right tool only when speed matters more than storage cost.

---

## Data Structure Choice

Six options evaluated for caching recent messages per group:

| Structure      | Fit           | Why                                                                            |
| -------------- | ------------- | ------------------------------------------------------------------------------ |
| String         | Bad           | Adding one message rewrites the entire JSON array — O(N) on every write        |
| Hash           | Bad           | No ordering — can't get "last 50 messages" without knowing all IDs             |
| List           | Not ideal     | Ordered by insertion, not timestamp — delayed messages break chronology        |
| **Sorted Set** | **Perfect**   | Score = timestamp → chronological order, range queries native, O(log N) insert |
| Pub/Sub        | Different use | No persistence — used for cross-server WebSocket broadcast, not caching        |
| Streams        | Overkill      | Powerful but complex — designed for event streaming, not a cache layer         |

**Choice: Sorted Set for message cache. Pub/Sub for cross-server broadcast.**

---

## Key Design

```
Key:    group:messages:{groupId}
Type:   Sorted Set
Score:  created_at epoch milliseconds
Member: serialized message JSON
```

Operations used:

```
Add new message:
ZADD group:messages:{groupId} {epochMs} '{message JSON}'

Get last 50 messages (newest first):
ZREVRANGE group:messages:{groupId} 0 49

Cursor-based pagination (messages before a timestamp):
ZREVRANGEBYSCORE group:messages:{groupId} {cursorMs} -inf LIMIT 0 50

Trim to max 50 entries (remove oldest when size exceeds limit):
ZREMRANGEBYRANK group:messages:{groupId} 0 -51
```

---

## Cache Patterns

### Write-Through (our write path)

```
Kafka consumer persists message to PostgreSQL
    → also ZADDs to Redis Sorted Set immediately
Cache always in sync with DB. No stale reads.
Extra write on every insert — fine since it's on the cold path (async).
```

### Cache-Aside (our read path)

```
GET /messages request arrives
    → Check Redis → HIT  → return immediately (~1ms)
    → Check Redis → MISS → query PostgreSQL → write to Redis → return (~15ms)
```

### Write-Behind — Ruled Out

Write to Redis first, async flush to DB later. Fastest writes, but if Redis crashes before flush — data loss. Not acceptable for chat messages.

---

## Cache Eviction

### TTL — Primary Strategy

Every group message key gets TTL = 24 hours. Groups with no activity evicted automatically. Active groups stay warm because TTL is refreshed on every read.

```
On write:  ZADD group:messages:{groupId} ... then EXPIRE group:messages:{groupId} 86400
On read:   EXPIRE group:messages:{groupId} 86400   ← refresh TTL
```

### Max Entries per Group — 50 Messages

After every ZADD, trim to 50 to keep memory predictable:

```
ZREMRANGEBYRANK group:messages:{groupId} 0 -51
```

Removes the oldest entry when size exceeds 50. History beyond 50 is served from PostgreSQL.

### LRU Eviction — Safety Net

Redis configured with `maxmemory-policy: allkeys-lru`. When Redis hits its memory ceiling, it evicts least recently accessed keys automatically. Catches anything TTL misses.

---

## Cache Stampede Prevention

**The problem:** A popular group's cache key expires. 500 users open the app at the same moment. All 500 find a miss and fire DB queries simultaneously. DB gets hammered.

**Solution: Distributed lock with SET NX**

```
Thread 1: SET lock:group:{groupId} "1" NX PX 5000  → succeeds (gets lock)
          queries PostgreSQL → writes result to Redis
          DEL lock:group:{groupId}

Thread 2: SET lock:group:{groupId} "1" NX PX 5000  → fails (lock held)
          waits 50ms → retries Redis read → hits warm cache now ✓
```

- `NX` = only set if key does not exist (atomic check + set)
- `PX 5000` = auto-expire lock after 5 seconds (prevents deadlock if Thread 1 crashes)
- Only one thread hits DB. All others wait and read from the now-warm cache.

---

## Cross-Server Broadcast (Pub/Sub)

When running multiple app servers, a message received by Server 1 must reach users connected to Server 2 and Server 3. Redis Pub/Sub handles this:

```
Server 1 receives message
    ├── broadcasts to its own local STOMP sessions
    └── PUBLISH group:messages:{groupId} '{message JSON}'
            ├── Server 2 subscribed → broadcasts to its local sessions ✓
            └── Server 3 subscribed → broadcasts to its local sessions ✓
```

Pub/Sub is ephemeral — no persistence, no history. Right tool for this because we only need to reach users online right now. History is served from the Sorted Set cache or PostgreSQL.

---

## Memory Calculation

```
Average message JSON:          ~200 bytes
Sorted Set overhead per entry: ~64 bytes
50 messages per group:         ~13 KB per group
1,000 active groups:           ~13 MB total
10,000 active groups:          ~130 MB total
```

Extremely manageable. A 512 MB Redis instance handles tens of thousands of active groups with room to spare.

---

## When Redis Is NOT the Right Tool

| Scenario           | Why Redis is wrong                                   |
| ------------------ | ---------------------------------------------------- |
| Primary storage    | Not durable by default — RAM only, crashes lose data |
| Complex queries    | No SQL, no joins, no aggregations                    |
| Large datasets     | RAM is expensive — can't store full message history  |
| Strong consistency | Redis Cluster is eventually consistent across nodes  |
| Relational data    | Use PostgreSQL                                       |

Redis is right only when you need sub-millisecond access to a bounded, frequently-read dataset. Recent messages per group fits perfectly.

---

## Final Decisions

| Decision               | Choice                                | Why                                                           |
| ---------------------- | ------------------------------------- | ------------------------------------------------------------- |
| Data structure         | Sorted Set                            | Score = timestamp → chronological order, range queries native |
| Key pattern            | `group:messages:{groupId}`            | One sorted set per group                                      |
| Max entries            | 50 per group                          | Covers most reads; history beyond 50 falls to DB              |
| Write pattern          | Write-through                         | Kafka consumer writes to DB + Redis together                  |
| Read pattern           | Cache-aside                           | Check Redis first, DB on miss                                 |
| TTL                    | 24 hours (refreshed on read)          | Inactive groups evicted, active groups stay warm              |
| Eviction policy        | `allkeys-lru`                         | Safety net when memory ceiling is hit                         |
| Stampede prevention    | SET NX distributed lock               | Only one thread hits DB on concurrent cache miss              |
| Cross-server broadcast | Pub/Sub on `group:messages:{groupId}` | Ephemeral fan-out — no storage needed                         |
| Max memory             | 512 MB                                | Sufficient for 1000 DAUs with large headroom                  |
