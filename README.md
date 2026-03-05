# TripChat

A real-time group chat application built for travelers to communicate and coordinate. Supports 1000+ daily active users with sub-100ms message delivery.

**Live App:** https://d2c6hrvg5t8xkc.cloudfront.net

---

## Features

- **Authentication** — Register / login with JWT tokens (24h TTL)
- **Group Management** — Create groups, join via 8-character invite codes, leave groups, regenerate invite codes (admin)
- **Real-time Messaging** — WebSocket + STOMP protocol with SockJS fallback
- **Typing Indicators** — See who is typing in real time
- **Presence Tracking** — Online/offline status with 20s heartbeat (30s Redis TTL)
- **Unread Counts** — Per-group unread message counters
- **Infinite Scroll** — Cursor-based message pagination (no offset, more efficient)
- **Optimistic UI** — Messages appear instantly with clientId deduplication
- **Message Deletion** — Senders can delete their own messages
- **Kafka Durability** — Outbox pattern ensures no message loss on failure

---

## System Design

```mermaid
flowchart TD
    subgraph CLIENT["CLIENT — React 19 · TypeScript · Zustand · TanStack Query · STOMP/SockJS"]
        Auth[Auth feature]
        Groups[Groups feature]
        Chat[Chat feature]
        Presence[Presence feature]
        Axios[Axios — JWT interceptor]
        STOMP[STOMP Client — WebSocket]
        Auth & Groups --> Axios
        Chat & Presence --> STOMP
    end

    Axios -->|HTTPS REST| REST
    STOMP -->|WSS WebSocket| WS

    subgraph BACKEND["BACKEND — Spring Boot 3 · Java 21"]
        REST["REST API\n/api/v1/**"]
        WS["WebSocket/STOMP\n/ws endpoint"]
        KC["Kafka Consumer\nchat.messages"]
        SVC["Service Layer\nAOP timed · < 20ms"]
        REST & WS & KC --> SVC
    end

    SVC --> PG[("PostgreSQL\nRDS")]
    SVC --> RD[("Redis\nElastiCache")]
    SVC -->|publish| KF[("Kafka\nMSK Serverless")]
    KF -->|consume| KC
```

### Message Flow

```mermaid
flowchart TD
    A([User sends message]) --> B[STOMP /app/groups/id/messages]
    B --> C[MessageController → MessageService]
    C --> D[(Write Message + Outbox\nsame DB transaction)]
    D --> E[OutboxPublisher → Kafka\nchat.messages · 3 partitions]
    E --> F[ChatMessageConsumer]
    F --> G[(Cache in Redis\nsub-ms reads)]
    F --> H[Broadcast via STOMP\n/topic/groups/id/messages]
    H --> I([All group members\nreceive in real time])
```

### Latency Targets

| Operation               | Target  |
|-------------------------|---------|
| Server-side processing  | < 20ms  |
| Send → all receive      | < 100ms |
| Message load (cache hit)| < 1ms   |
| Message load (cache miss)| < 15ms  |

---

## Tech Stack

### Frontend
| Layer | Technology |
|---|---|
| Framework | React 19 + TypeScript |
| Build | Vite 7 |
| Routing | React Router v7 |
| State | Zustand 5 |
| Server State | TanStack Query v5 |
| HTTP | Axios (JWT interceptor + 401 logout) |
| WebSocket | STOMP.js + SockJS |
| Forms | React Hook Form + Zod |
| Styling | Tailwind CSS v4 |
| Testing | Vitest + Playwright + MSW |

### Backend
| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
| Database | PostgreSQL 16 (Spring Data JPA + HikariCP) |
| Cache | Redis 7 (Lettuce, non-blocking) |
| Messaging | Apache Kafka 3 (Outbox pattern, manual ack) |
| WebSocket | Spring WebSocket + STOMP |
| Auth | Spring Security 6 + JWT (jjwt 0.12) |
| Observability | Spring AOP (execution timing) + Actuator |
| Testing | JUnit 5 + Mockito + AssertJ |

---

## AWS Architecture

```mermaid
flowchart TD
    User([User]) --> CF

    subgraph AWS["AWS — us-east-1"]
        subgraph Edge["Edge / CDN"]
            CF["CloudFront\nd2c6hrvg5t8xkc.cloudfront.net"]
        end

        subgraph FE["Frontend"]
            S3["S3\ntripchat-frontend-010741811423\nReact build · OAC"]
        end

        subgraph App["Application Layer"]
            ALB["ALB\nLayer 7 · sticky sessions 1-day"]
            ECS["ECS Fargate\nSpring Boot 3\n0.5 vCPU · 1 GB RAM\n/actuator/health"]
        end

        subgraph Data["Data Layer"]
            RDS["RDS PostgreSQL 16\ndb.t3.micro · Single-AZ\nHikariCP max 20"]
            EC["ElastiCache Redis 7\nPresence · Cache · Typing · Unread"]
            MSK["MSK Serverless\nchat.messages · 3 partitions"]
        end

        subgraph Ops["DevOps & Security"]
            ECR["ECR\nDocker registry\n:sha + :latest tags"]
            SM["Secrets Manager\nJWT_SECRET · DB_PASSWORD"]
            CW["CloudWatch\nLogs · Metrics"]
            IAM["IAM Role\ntripchat-github-deploy\nGitHub OIDC"]
        end
    end

    subgraph CICD["GitHub Actions (CI/CD)"]
        GHA["Deploy workflows\nOIDC — no stored AWS keys"]
    end

    CF -->|"OAC — static assets"| S3
    CF -->|"HTTPS REST · WSS WebSocket"| ALB
    ALB --> ECS
    ECS --> RDS
    ECS --> EC
    ECS -->|publish| MSK
    MSK -->|consume| ECS
    ECS -->|pull image| ECR
    SM -->|inject secrets| ECS
    ECS -->|logs & metrics| CW
    GHA -->|AssumeRoleWithWebIdentity| IAM
    IAM -->|push image| ECR
    IAM -->|force-new-deployment| ECS
    IAM -->|s3 sync + delete| S3
    IAM -->|invalidate cache| CF
```

### CI/CD Pipeline

```mermaid
flowchart TD
    Push([Push to main]) --> Check{Changed files?}
    Check -->|frontend/**| FE[Deploy Frontend workflow]
    Check -->|backend/**| BE[Deploy Backend workflow]

    FE --> F1[npm ci + vite build]
    F1 --> F2[aws s3 sync --delete]
    F2 --> F3[CloudFront invalidation /*]

    BE --> B1[docker build ./backend]
    B1 --> B2[Push to ECR :sha + :latest]
    B2 --> B3[ecs update-service\n--force-new-deployment]
```

Authentication uses GitHub OIDC (no long-lived AWS keys stored in secrets).

---

## API Reference

### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/auth/register` | Register new user |
| `POST` | `/api/v1/auth/login` | Login, returns JWT |
| `GET` | `/api/v1/groups` | List user's groups |
| `POST` | `/api/v1/groups` | Create group |
| `POST` | `/api/v1/groups/join` | Join via invite code |
| `POST` | `/api/v1/groups/{id}/leave` | Leave group |
| `POST` | `/api/v1/groups/{id}/invite-code` | Regenerate invite code (admin) |
| `GET` | `/api/v1/groups/{id}/messages?cursor=&limit=20` | Paginated message history |
| `DELETE` | `/api/v1/messages/{id}` | Delete message (sender only) |
| `GET` | `/api/v1/groups/{id}/presence` | Online members in group |

### WebSocket (STOMP)

Connect to `/ws` with JWT in STOMP CONNECT frame.

| Direction | Destination | Description |
|-----------|-------------|-------------|
| Subscribe | `/topic/groups/{id}/messages` | Receive group messages |
| Subscribe | `/topic/groups/{id}/typing` | Typing indicators |
| Subscribe | `/topic/groups/{id}/presence` | Online/offline events |
| Subscribe | `/user/queue/notifications` | Personal notifications |
| Send | `/app/groups/{id}/messages` | Send a message |
| Send | `/app/groups/{id}/typing` | Send typing event |
| Send | `/app/presence/heartbeat` | Keep-alive (every 20s) |

---

## Local Development

### Prerequisites
- Docker + Docker Compose
- Java 21
- Node.js 20+

### Setup

```bash
git clone https://github.com/amitpatle10/TripChat.git
cd TripChat
cp .env.example .env    # fill in JWT_SECRET (64-char hex)
docker compose up -d    # starts postgres, redis, kafka, backend, frontend
```

App runs at `http://localhost:5173` (frontend) and `http://localhost:8080` (backend).

### Run individually

```bash
# Backend
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Frontend
cd frontend && npm install && npm run dev
```

### Tests

```bash
# Backend
cd backend && ./mvnw test

# Frontend unit tests
cd frontend && npm test

# Frontend E2E
cd frontend && npm run test:e2e
```

---

## Cost Estimate

~$70–100/month at 1000 DAUs (ECS Fargate + RDS t3.micro + ElastiCache + MSK Serverless + CloudFront)
