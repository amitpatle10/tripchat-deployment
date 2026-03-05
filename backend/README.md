# TripChat Backend

Real-time group chat backend for TripPlanner. Built with Spring Boot, Kafka, Redis, and PostgreSQL — designed for low-latency group messaging at 1000 DAUs.

## Tech Stack

| Layer     | Technology             |
| --------- | ---------------------- |
| Language  | Java 21                |
| Framework | Spring Boot 3.4.3      |
| Build     | Maven 3.9.9            |
| Database  | PostgreSQL             |
| Cache     | Redis (Lettuce client) |
| Messaging | Apache Kafka           |
| Real-time | WebSocket + STOMP      |
| Auth      | JWT (jjwt 0.12.6)      |

## Features

- User registration and login (JWT auth)
- Create / join / leave groups with invite codes
- Real-time group messaging (WebSocket + STOMP)
- Kafka-backed message pipeline with outbox pattern
- Redis cache for sub-millisecond message reads
- Typing indicators
- Online presence tracking
- Unread message counts
- Infinite scroll with cursor-based pagination

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+
- PostgreSQL 14+
- Redis 7+
- Apache Kafka 3+

## Local Setup

### 1. Start infrastructure services

**PostgreSQL**

```bash
brew services start postgresql@14
```

Create the database and user:

```sql
psql postgres
CREATE USER tripchat WITH PASSWORD 'tripchat';
CREATE DATABASE tripchat OWNER tripchat;
\q
```

**Redis**

```bash
brew services start redis
```

**Kafka**

```bash
brew services start kafka
```

Create the required topic (first time only):

```bash
kafka-topics --bootstrap-server localhost:9092 \
  --create --topic chat.messages \
  --partitions 3 --replication-factor 1
```

### 2. Configure environment (optional)

The app has sensible defaults for local development. Override any of these if needed:

| Variable         | Default          | Description                                     |
| ---------------- | ---------------- | ----------------------------------------------- |
| `DB_USERNAME`    | `tripchat`       | PostgreSQL username                             |
| `DB_PASSWORD`    | `tripchat`       | PostgreSQL password                             |
| `REDIS_HOST`     | `localhost`      | Redis host                                      |
| `REDIS_PORT`     | `6379`           | Redis port                                      |
| `REDIS_PASSWORD` | _(empty)_        | Redis password                                  |
| `KAFKA_SERVERS`  | `localhost:9092` | Kafka bootstrap servers                         |
| `JWT_SECRET`     | _(dev default)_  | JWT signing secret — **override in production** |

### 3. Run the application

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Or run in the background with logs:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run -Dspring-boot.run.profiles=dev > /tmp/tripchat.log 2>&1 &
tail -f /tmp/tripchat.log
```

The server starts on **http://localhost:8080**.

## Running Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

88 unit tests across auth, JWT, groups, messaging, presence, typing, and unread services. All tests use Mockito — no external dependencies required.

## API Overview

### Auth

| Method | Endpoint                | Description           |
| ------ | ----------------------- | --------------------- |
| `POST` | `/api/v1/auth/register` | Register a new user   |
| `POST` | `/api/v1/auth/login`    | Login and receive JWT |

### Groups

| Method   | Endpoint                                     | Description                    |
| -------- | -------------------------------------------- | ------------------------------ |
| `POST`   | `/api/v1/groups`                             | Create a group                 |
| `POST`   | `/api/v1/groups/join`                        | Join via invite code           |
| `DELETE` | `/api/v1/groups/{id}/leave`                  | Leave a group                  |
| `GET`    | `/api/v1/groups/{id}`                        | Get group details              |
| `POST`   | `/api/v1/groups/{id}/invite-code/regenerate` | Regenerate invite code (admin) |

### Messages (REST)

| Method | Endpoint                       | Description                          |
| ------ | ------------------------------ | ------------------------------------ |
| `GET`  | `/api/v1/groups/{id}/messages` | Fetch message history (cursor-based) |

### Messages (WebSocket / STOMP)

| Direction | Destination                 | Description            |
| --------- | --------------------------- | ---------------------- |
| Publish   | `/app/groups/{id}/send`     | Send a message         |
| Subscribe | `/topic/groups/{id}`        | Receive group messages |
| Publish   | `/app/groups/{id}/typing`   | Send typing indicator  |
| Subscribe | `/topic/groups/{id}/typing` | Receive typing events  |

Connect to WebSocket at `ws://localhost:8080/ws` with the JWT token as a STOMP header.

### Presence (REST)

| Method | Endpoint                       | Description              |
| ------ | ------------------------------ | ------------------------ |
| `GET`  | `/api/v1/groups/{id}/presence` | Get online members       |
| `GET`  | `/api/v1/groups/{id}/unread`   | Get unread message count |

## Project Structure

```
src/main/java/com/tripchat/
├── controller/        # REST + STOMP controllers
├── service/           # Business logic
├── repository/        # Spring Data JPA repositories
├── model/             # JPA entities + enums
├── dto/               # Request / response DTOs
├── messaging/         # Kafka consumer + outbox relay
├── security/          # JWT filter, strategy, config
├── config/            # Kafka, WebSocket, AOP config
├── exception/         # Global exception handler + custom exceptions
└── util/              # Invite code generator

src/test/java/com/tripchat/
├── security/          # JWT tests
├── service/           # Auth, group, message, presence, typing, unread tests
└── messaging/         # Outbox relay tests
```

## Messaging Architecture

```
Client
  │
  ▼ WebSocket / STOMP
Controller
  │
  ▼ persist
Outbox table (PENDING)
  │
  ├──► STOMP broadcast → all subscribers (hot path, ~10ms)
  │
  └──► OutboxRelay (100ms poll)
         │
         ▼ publish
       Kafka topic: chat.messages (3 partitions, key=groupId)
         │
         ▼ consume
       MessageKafkaConsumer
         │
         ├──► messages table (durable store)
         └──► Redis Sorted Set (50-entry cache, 24h TTL)
```

## Latency Targets

| Operation                  | Target  |
| -------------------------- | ------- |
| Server-side processing     | < 20ms  |
| Send → all members receive | < 100ms |
| Load messages (cache hit)  | < 1ms   |
| Load messages (cache miss) | < 15ms  |
