# How to Choose a Database

## The Core Principle

Never start with "which database is popular." Start with your data, your queries, and your scale. The database should fit the problem — not the other way around.

---

## Factor 1 — Data Model (Start Here)

First question: **what shape is your data?**

| Shape                                   | Example                        | Right Database    |
| --------------------------------------- | ------------------------------ | ----------------- |
| Structured, related entities with JOINs | Users, orders, groups          | PostgreSQL, MySQL |
| Flexible, nested JSON documents         | Product catalogue, CMS         | MongoDB           |
| Simple key → value lookups              | Sessions, cache                | Redis, DynamoDB   |
| Wide rows, time-series, write-heavy     | IoT events, logs, chat history | Cassandra         |
| Connections between entities            | Social graph, recommendations  | Neo4j             |
| Full-text search                        | Search engine, autocomplete    | Elasticsearch     |

This single factor eliminates most options. If your data is relational — you have JOINs, foreign keys, referential integrity — a document store or wide-column store will fight you constantly.

---

## Factor 2 — Access Patterns

**What queries will you run most?**

This is where most engineers go wrong. They choose a database based on data model alone, then discover their most frequent query is a nightmare.

| Query Pattern                       | Right Choice                       |
| ----------------------------------- | ---------------------------------- |
| Fetch by primary key                | Anything                           |
| JOIN across multiple tables         | Relational (PostgreSQL)            |
| Get recent items sorted by time     | Cassandra or PostgreSQL with index |
| Aggregations (COUNT, SUM, GROUP BY) | Relational DB                      |
| Fetch full document by ID           | MongoDB                            |
| Time-range scan on massive dataset  | Cassandra, TimescaleDB             |
| Full-text search                    | Elasticsearch                      |

### Cassandra's Golden Rule

In Cassandra, you design your schema around your queries — not your data. Every query must hit exactly one partition. It cannot JOIN. If you need two different queries on the same data, you create two separate tables (denormalized copies). This is Cassandra's core tradeoff: incredible write throughput and horizontal scale, in exchange for query flexibility.

---

## Factor 3 — Consistency Requirements (CAP Theorem)

Every distributed database makes a tradeoff between three properties. You can only guarantee two of them:

```
            Consistency
        (every read gets the latest write)
                /\
               /  \
              /    \
             / Pick \
            /   two  \
           /──────────\
    Availability      Partition Tolerance
    (always responds) (works despite network failures)
```

| Database            | Guarantees                              | Tradeoff                                     |
| ------------------- | --------------------------------------- | -------------------------------------------- |
| PostgreSQL          | CP — Consistency + Partition tolerance  | May reject requests during network partition |
| Cassandra           | AP — Availability + Partition tolerance | May return stale data (eventual consistency) |
| MongoDB             | Configurable — CP by default            | Depends on write concern settings            |
| Redis (single node) | CA — Consistency + Availability         | No partition tolerance                       |

### When Does This Matter?

**Need strong consistency (use PostgreSQL):**

- User registration and authentication
- Financial transactions
- Inventory management
- Group membership validation

```sql
BEGIN;
  INSERT INTO messages ...;
  UPDATE group_members SET last_read = now() ...;
COMMIT;
-- Either both happen or neither. No partial state. Ever.
```

**Eventual consistency is acceptable (Cassandra works):**

- Message history (50ms delay before all nodes sync is invisible to users)
- Social media feeds
- Analytics and metrics
- Activity logs

---

## Factor 4 — Scale Requirements

| Scale                 | Right Approach                               |
| --------------------- | -------------------------------------------- |
| Less than 10k DAUs    | Single PostgreSQL instance, vertical scaling |
| 10k to 1M DAUs        | PostgreSQL + read replicas + Redis caching   |
| 1M+ DAUs, write-heavy | Cassandra or sharded PostgreSQL              |
| 1M+ DAUs, read-heavy  | PostgreSQL + aggressive Redis caching        |

**Vertical scaling** — bigger machine (more RAM, faster CPU). PostgreSQL handles this extremely well. Surprisingly effective before you ever need horizontal scaling.

**Horizontal scaling** — more machines. Cassandra and MongoDB are designed for this from day one. PostgreSQL can scale reads via replicas, but write scaling is harder.

### The Scale Trap

Engineers often choose Cassandra or MongoDB for horizontal scaling before they need it. The complexity cost is real and immediate. The scaling benefit is future and uncertain. At under 1 million DAUs, PostgreSQL on a properly sized instance with good indexes and caching handles load comfortably.

Optimize for the problem you have today, not the one you might have in two years.

---

## Factor 5 — Operational Cost

A database you understand beats a theoretically superior database you don't.

| Question                           | Why It Matters                                                    |
| ---------------------------------- | ----------------------------------------------------------------- |
| Does your team know this database? | Learning curve is real cost                                       |
| Is a managed service available?    | Self-hosting Cassandra is significant ops burden                  |
| How painful are schema migrations? | Cassandra migrations are very hard; PostgreSQL has mature tooling |
| What does monitoring look like?    | PostgreSQL tooling is decades mature                              |

Cassandra is powerful. It is also operationally complex. Running it for 1000 daily active users is like buying a freight truck to deliver pizza.

---

## Database Comparison — The Big Three

### PostgreSQL

**Best for:** Relational data, complex queries, ACID transactions, moderate scale.

**Strengths:**

- Full ACID compliance — transactions that never leave data in a broken state
- Rich query language — JOINs, aggregations, window functions, CTEs
- Mature ecosystem — decades of tooling, monitoring, migration support
- Handles 10M+ rows comfortably with proper indexing
- Read replicas for horizontal read scaling

**Weaknesses:**

- Write scaling requires sharding (complex to implement)
- Schema changes on massive tables can be slow
- Not designed for massive horizontal write throughput

**Use when:** Your data has relationships, you need JOINs, you need transactions, your team knows SQL.

---

### MongoDB

**Best for:** Flexible document data, rapid schema evolution, moderate scale.

**Strengths:**

- Schema-less — add fields without migrations
- Native JSON storage — great for nested, variable-structure data
- Built-in horizontal sharding
- Fast for document-level reads and writes

**Weaknesses:**

- No JOINs (workarounds exist but are inefficient)
- Multi-document transactions are possible but complex
- Flexible schema is a double-edged sword — data consistency becomes your problem
- Often chosen for the wrong reasons (it's "web scale" is a myth)

**Use when:** Your data is truly document-shaped, schema changes frequently, you don't need JOINs.

---

### Cassandra

**Best for:** Massive write throughput, time-series data, true horizontal scale.

**Strengths:**

- Linear horizontal scaling — add nodes, get proportional throughput
- Designed for write-heavy workloads
- No single point of failure — fully distributed
- Excellent for time-series: recent messages, IoT events, logs
- Used by Discord, Netflix, Instagram at billions of rows

**Weaknesses:**

- No JOINs — ever
- Eventual consistency only — stale reads are normal
- Schema must be designed around queries (inflexible to changing access patterns)
- Operationally complex to run and tune
- Painful schema migrations

**Use when:** You have massive write volume, time-series data, need true horizontal scale, and your team can handle the operational complexity.

---

## The Polyglot Persistence Pattern

The real insight: **don't pick one database for everything.** Use the right database for each specific need within the same system.

```
PostgreSQL    →  relational data (users, groups, membership, messages)
                 ACID, JOINs, consistency, complex queries

Redis         →  hot cache + real-time pub/sub
                 sub-millisecond reads, ephemeral, bounded dataset

Kafka         →  event streaming pipeline
                 durable, replayable, fan-out to multiple consumers

Elasticsearch →  full-text search (when needed)
                 search, autocomplete, analytics
```

Each tool does exactly what it is best at. No single tool is forced to do everything poorly.

### Real-World Example — TripChat at Different Scales

**At 1,000 DAUs (current):**

```
PostgreSQL  →  everything relational (users, groups, messages)
Redis       →  message cache + WebSocket pub/sub
Kafka       →  message pipeline
```

**At 10,000,000 DAUs (future):**

```
PostgreSQL  →  users, groups, membership (relational, manageable volume)
Cassandra   →  messages (write-heavy, time-series, billions of rows)
Redis       →  cache + pub/sub (same)
Kafka       →  pipeline (same)
Elasticsearch → message search
```

Notice: the relational data stays in PostgreSQL. Only the part of the system that actually needs Cassandra's strengths moves there.

---

## The Decision Checklist

Work through this in order. Stop when you have your answer.

```
Step 1 — What shape is my data?
         Relational with JOINs?        → PostgreSQL
         Flexible nested documents?    → MongoDB
         Time-series, write-heavy?     → Cassandra
         Key-value, ephemeral?         → Redis

Step 2 — What queries do I need?
         Complex JOINs or aggregations? → Must be relational
         Simple key lookup?            → Almost anything
         Time-range scan at scale?     → Cassandra

Step 3 — What consistency do I need?
         ACID transactions required?   → PostgreSQL
         Eventual consistency OK?      → Cassandra or MongoDB

Step 4 — What is my actual scale today?
         Under 1M DAUs?               → PostgreSQL + caching handles it
         Over 1M write-heavy?         → Consider Cassandra

Step 5 — What is my team's familiarity?
         Don't introduce Cassandra complexity for 1000 DAUs
         Operational cost is a real cost — factor it in

Step 6 — Do I need multiple databases?
         Right tool for each job → Polyglot persistence
         Don't force one database to do everything
```

---

## Summary

| Factor          | Question to Ask                                                   |
| --------------- | ----------------------------------------------------------------- |
| Data model      | What shape is my data — relational, document, time-series?        |
| Access patterns | What queries do I run most? Can this DB handle them efficiently?  |
| Consistency     | Do I need ACID guarantees, or is eventual consistency acceptable? |
| Scale           | What is my actual load today, not hypothetical future load?       |
| Operations      | Can my team run and maintain this database?                       |
| Polyglot        | Should different parts of my system use different databases?      |

The best database is the one that fits your data model, handles your access patterns efficiently, meets your consistency requirements, and matches your team's ability to operate it — at the scale you actually have today.
