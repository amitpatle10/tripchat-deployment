# TripChat Backend — Build Guide

> **Stack:** Spring Boot · Kafka · Redis · PostgreSQL  
> **Scale:** 1000 DAUs, low-latency group chat

---

## Features (Phase 1)

- User registration and login
- Create / join / leave groups
- Real-time group messaging (WebSocket + STOMP)
- Kafka-backed message pipeline
- Redis cache for sub-millisecond reads
- Typing indicators & online presence
- Unread counts
- Infinite scroll message history

---

## Principles

- **SOLID throughout.** We discuss where each principle applies as we build.
- **Test every API immediately.** curl it, confirm it, before moving forward.
- **Measure with Spring AOP.** Log execution time on every service method. If slow, we stop and optimize.
- **Robust exception handling for every API.** Each endpoint must have proper error handling
- **Comprehensive test cases for every API.** Maintain a dedicated test folder (`src/test/`) mirroring the main package structure. For each API, write tests covering all scenarios — happy path, validation failures, auth errors, edge cases, and downstream failures.
- **Design patterns — name them, explain them.** Whenever we use a pattern (Strategy, Observer, Factory, Builder, Singleton, etc.), call it out explicitly — why this pattern here, what problem it solves, and what the alternative would be. Never silently apply a pattern.
- **API design patterns & best practices.** Follow RESTful conventions strictly — proper HTTP methods, status codes, resource naming, pagination (cursor-based for chat), versioning, idempotency for writes, and HATEOAS where it adds value. Discuss tradeoffs (e.g., REST vs GraphQL, cursor vs offset pagination) before choosing.
- **Kafka deep dives.** Before using Kafka in any feature, explain the internals — topics, partitions, consumer groups, offsets, rebalancing, at-least-once vs exactly-once semantics, producer acks, retention policies, and dead letter queues. Discuss why Kafka over alternatives (RabbitMQ, Redis Streams) and the tradeoffs we're making.
- **Redis deep dives.** Before using Redis in any feature, explain the data structure choice (Strings, Hashes, Sorted Sets, Pub/Sub, Streams) and why. Cover cache eviction strategies (LRU, TTL), cache-aside vs write-through vs write-behind patterns, cache stampede prevention, and memory implications. Discuss when Redis is the right tool vs when it isn't.
- **Tradeoff analysis at every decision point.** No architectural choice is made without discussing at least two alternatives and their tradeoffs — consistency vs availability, latency vs throughput, simplicity vs scalability, memory vs compute. Document the "why" behind each decision as inline comments or in this doc.
- **Concurrency & thread safety.** Explain threading models as they come up — how Spring handles concurrent requests, Kafka consumer threading, Redis connection pooling, WebSocket session management. Identify potential race conditions and how we prevent them.
- **Database design with intent.** Discuss schema decisions — normalization vs denormalization tradeoffs, indexing strategy, query optimization, N+1 prevention, connection pooling. Explain why each table/column exists.
- **One topic at a time.** Never discuss multiple tools, concepts, or technologies in a single response. Finish the current discussion completely — reach a decision, get agreement — before moving to the next topic. For example, if we're discussing Kafka, complete that discussion before bringing up Redis or WebSocket.
- **No code before discussion.** Never write or edit any code until the concept, design pattern, tradeoff, and approach have been fully discussed and agreed upon. Discussion always comes first — code follows only after understanding is clear.
- **Learn by discussion.** I present structure & tradeoffs, you decide, then we build.

---

## Approach

At each step:

1. **Discuss first (mandatory)** — structure, tradeoffs, alternatives, and the design patterns / concepts involved. **No file is created or edited until this step is complete and agreed upon.**
2. **Why** — explain why this approach over alternatives, name the pattern being used
3. **Build** — small increments, commented code (only after steps 1 & 2 are done)
4. **Test** — curl / wscat, verify response + run test suite
5. **Measure** — check AOP timing, optimize if needed
6. **Reflect** — summarize what was learned (pattern, tradeoff, or concept)

Steps emerge from the conversation. We go one at a time.

---

## Latency Targets

| Operation                  | Our Target | WhatsApp Reference |
| -------------------------- | ---------- | ------------------ |
| Server-side processing     | < 20ms     | < 5ms              |
| Send → all members receive | < 100ms    | ~100ms             |
| Load messages (cache hit)  | < 1ms      | < 1ms              |
| Load messages (cache miss) | < 15ms     | —                  |
