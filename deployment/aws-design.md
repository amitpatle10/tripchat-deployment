# TripChat — AWS Deployment Design

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [AWS Services Used](#aws-services-used)
3. [System Design Diagram](#system-design-diagram)
4. [Infrastructure Resource Inventory](#infrastructure-resource-inventory)
5. [Deployment Sequence](#deployment-sequence)
6. [Code Changes Required for Production](#code-changes-required-for-production)
7. [Issues Faced & Fixes Applied](#issues-faced--fixes-applied)
8. [Things to Handle Carefully](#things-to-handle-carefully)
9. [Cost Estimate](#cost-estimate)
10. [Pending Work](#pending-work)

---

## Architecture Overview

TripChat is a real-time group chat application with a React frontend and a Spring Boot backend. The production architecture separates concerns into three tiers:

- **Frontend** — React SPA served as static files via S3 + CloudFront (CDN)
- **Backend** — Spring Boot containerized application running on ECS Fargate behind an ALB
- **Data layer** — RDS PostgreSQL (relational), ElastiCache Redis (cache/pub-sub), MSK Serverless Kafka (event streaming)

All data-layer services live in **private subnets**. The ECS tasks run in **public subnets** with security group restrictions (no NAT Gateway needed, saving ~$32/month).

---

## AWS Services Used

### 1. Amazon CloudFront
**What it does:** Global CDN that caches and serves the React static build from S3 edge locations worldwide.

**Why we chose it over alternatives:**
- **vs. serving directly from S3:** S3 static website hosting lacks HTTPS on custom domains and has high latency for geographically distant users. CloudFront adds HTTPS termination (via ACM), global edge caching, and custom error pages for SPA routing.
- **vs. serving from EC2/Nginx:** A dedicated server for static files is expensive and operationally heavy for content that never changes between deploys.

**Why it matters for TripChat:**
- SPA routing fix — configures 403/404 → `index.html` (200) so React Router handles all paths client-side.
- Routes `/api/*` and `/ws*` to the ALB backend, so the frontend uses a single origin for both static assets and API calls.
- HTTPS enforcement for the frontend at zero SSL cost (ACM is free).

---

### 2. Amazon S3
**What it does:** Stores the compiled React build artifacts (`dist/`). CloudFront uses it as the origin for static content.

**Why we chose it over alternatives:**
- **vs. EC2 with Nginx:** S3 is infinitely scalable, zero maintenance, and costs fractions of a cent per GB. No server to patch or monitor.
- **vs. EFS:** EFS is a network file system meant for shared compute access, not static file hosting.

**Configuration:**
- Block all public access — CloudFront accesses it via OAC (Origin Access Control), not public URLs.
- Bucket versioning enabled — safe rollbacks by pointing CloudFront to a previous object version.

---

### 3. Application Load Balancer (ALB)
**What it does:** Receives HTTPS requests from CloudFront and distributes them across ECS Fargate task IPs.

**Why we chose it over alternatives:**
- **vs. Network Load Balancer (NLB):** ALB operates at Layer 7 (HTTP/HTTPS) and supports path-based routing, sticky sessions, and WebSocket upgrades — all required for TripChat. NLB is Layer 4 (TCP), has no HTTP routing, and sticky sessions are based on IP only.
- **vs. API Gateway:** API Gateway has a 29-second timeout (breaks long-lived WebSocket STOMP sessions), higher cost at scale, and requires significant configuration for WebSocket proxy. ALB supports WebSocket natively with no timeout limits.

**Why it matters for TripChat:**
- **Sticky sessions** (duration-based cookie, 1-day TTL) — critical for WebSocket. STOMP establishes a stateful session on a specific ECS task. Without stickiness, a reconnect lands on a different task that has no subscription state.
- **Health checks** — polls `/actuator/health` every 30s; unhealthy tasks are drained before new ones are promoted.

---

### 4. Amazon ECS Fargate
**What it does:** Runs the Spring Boot Docker container without managing EC2 instances.

**Why we chose it over alternatives:**
- **vs. EC2 (self-managed):** EC2 requires OS patching, capacity planning, and manual scaling. Fargate is serverless compute — you define CPU/memory, AWS handles everything else.
- **vs. ECS on EC2:** EC2-backed ECS still requires managing the underlying instances. Fargate removes that entirely.
- **vs. Lambda:** Lambda has a 15-minute timeout and cold-start latency. TripChat has long-lived WebSocket connections that Lambda cannot support.
- **vs. EKS (Kubernetes):** Kubernetes is operationally complex for a single-service backend. ECS Fargate achieves the same container orchestration with far less configuration overhead.

**Configuration:**
- 0.5 vCPU / 1 GB RAM (suitable for 1000 DAUs)
- Public subnet placement with security groups instead of NAT Gateway (saves ~$32/month)
- Task role (IAM) for MSK IAM auth — no static credentials in the container

---

### 5. Amazon ECR (Elastic Container Registry)
**What it does:** Private Docker registry for the Spring Boot backend image.

**Why we chose it over alternatives:**
- **vs. Docker Hub:** Docker Hub has rate limits on pulls (100/6h on free tier) which can cause ECS deployment failures under load. ECR is co-located with ECS in the same AWS region — pulls are fast, free within the region, and never rate-limited.
- **vs. GitHub Container Registry:** Requires outbound internet access from ECS. ECR uses AWS-internal networking.

**Key detail:** Images are tagged with explicit version tags (`v7-topic-init`, not just `latest`) to ensure ECS pulls the correct image and enables rollback by tag.

---

### 6. Amazon RDS PostgreSQL 16
**What it does:** Primary relational database storing users, groups, messages, memberships.

**Why we chose it over alternatives:**
- **vs. Aurora PostgreSQL:** Aurora is 3–5x the cost of RDS for the same workload. For TripChat at 1000 DAUs, RDS `db.t3.micro` is sufficient and costs ~$15/month vs. ~$60/month for Aurora.
- **vs. DynamoDB:** TripChat's data model is highly relational — users join groups, messages belong to groups, memberships link users and groups. Complex JOIN queries and transactional integrity are native to PostgreSQL. DynamoDB's single-table design would require significant data modelling effort with no clear advantage.
- **vs. self-hosted PostgreSQL on EC2:** RDS provides automated backups, point-in-time recovery, minor version patching, and Multi-AZ failover out of the box.

**Configuration:**
- `db.t3.micro`, Single-AZ (cost optimization for dev/staging)
- Hikari connection pool max size: 20 (RDS t3.micro supports ~80 connections; 20 leaves headroom for admin tooling)
- `ddl-auto: validate` in production — Hibernate validates schema but never modifies it

---

### 7. Amazon ElastiCache Redis 7
**What it does:** In-memory cache and pub-sub broker for session data, presence tracking, typing indicators, and unread counts.

**Why we chose it over alternatives:**
- **vs. Memcached:** Memcached is a simple key-value store with no persistence, pub-sub, or rich data structures. Redis supports Sorted Sets (for message ordering), pub-sub (for presence events), Hashes (for session metadata), and optional AOF persistence.
- **vs. Redis on EC2:** No cluster management, automatic failover available, monitoring built into CloudWatch.
- **vs. DynamoDB for cache:** DynamoDB single-digit millisecond latency is good but still 10–50x slower than Redis sub-millisecond reads. For a chat app where presence and typing indicators fire on every keystroke, Redis is the correct choice.

**Why it matters for TripChat:**
- Sub-millisecond reads for cached message history
- Presence heartbeat TTL — users are marked offline automatically when TTL expires (30s)
- Typing indicator fan-out without hitting the database

---

### 8. Amazon MSK Serverless (Kafka)
**What it does:** Managed Kafka service that decouples message production (REST API) from message consumption (WebSocket fan-out).

**Why we chose it over alternatives:**
- **vs. MSK Provisioned:** Provisioned MSK requires choosing broker instance types, storage provisioning, and paying for idle capacity. Serverless is pay-per-use (per GB ingested/egested) with zero broker management.
- **vs. SQS:** SQS is a simple queue with no consumer group semantics, no offset replay, and no partition-based parallelism. Kafka's consumer groups allow multiple ECS tasks to each consume a partition, enabling horizontal scaling.
- **vs. SNS + SQS fan-out:** Complex to set up for dynamic topic routing. Kafka topics map naturally to chat message streams.
- **vs. Redis Pub/Sub:** Redis pub-sub is not durable — if a consumer is offline when a message is published, it is lost. Kafka retains messages for the configured retention period (default 7 days), enabling at-least-once delivery.

**Why it matters for TripChat:**
- At-least-once delivery with manual offset commit — messages survive app crashes
- `chat.messages` topic with 3 partitions — 3 ECS tasks can each own a partition for true parallelism at scale
- IAM authentication — no username/password; ECS task role provides credentials via AWS STS

---

### 9. AWS Secrets Manager
**What it does:** Stores sensitive credentials (`JWT_SECRET`, `DB_PASSWORD`) and injects them into the ECS container at runtime via the `secrets` block in the task definition.

**Why we chose it over alternatives:**
- **vs. SSM Parameter Store:** Secrets Manager supports automatic rotation (can trigger a Lambda to rotate the RDS password and update the secret). Parameter Store SecureString requires manual rotation.
- **vs. environment variables in plain text:** Credentials in plain-text env vars are visible in ECS console, CloudTrail logs, and `docker inspect` output. Secrets Manager keeps them encrypted at rest (AES-256) and in transit.
- **vs. HashiCorp Vault:** Vault requires a dedicated server and operational overhead. Secrets Manager is fully managed.

---

### 10. AWS IAM (Roles)
**Two roles used:**

| Role | Purpose |
|---|---|
| `tripchat-ecs-task-execution-role` | Used by ECS **agent** to pull images from ECR and write logs to CloudWatch. Not accessible inside the container. |
| `tripchat-ecs-task-role` | Used by the **application code** inside the container. Grants MSK IAM authentication (SASL_SSL with AWS_MSK_IAM). |

**Why two roles:** Principle of least privilege. The application should never have permission to pull Docker images or write logs — that's the agent's job. Mixing them into one role would violate least-privilege.

---

### 11. Amazon Route 53 (Pending)
**What it does:** DNS management and domain registration.

**Why over alternatives:**
- **vs. external registrar + external DNS:** Route 53 Alias records can point directly to ALB and CloudFront ARNs without TTL propagation delay. External DNS providers require CNAME records which cannot be used at zone apex (`yourdomain.com`, only `www.yourdomain.com`).

---

### 12. AWS Certificate Manager (ACM) (Pending)
**What it does:** Free SSL/TLS certificates for CloudFront and ALB HTTPS listeners.

**Why over alternatives:**
- **vs. Let's Encrypt:** Let's Encrypt certificates must be renewed every 90 days. ACM auto-renews and auto-deploys to CloudFront/ALB with zero intervention.

---

## System Design Diagram

```
                              Internet
                                 │
                    ┌────────────┼─────────────────┐
                    │                               │
         ┌──────────▼──────────┐         ┌─────────▼─────────┐
         │     CloudFront      │         │  CloudFront (same) │
         │  <id>.cloudfront.   │         │  /api/*  /ws*      │
         │  net                │         │  behavior → ALB    │
         └──────────┬──────────┘         └─────────┬─────────┘
                    │                               │
         ┌──────────▼──────────┐                   │
         │         S3          │                   │
         │  tripchat-frontend  │         ┌─────────▼─────────┐
         │  React build assets │         │   ALB (HTTP :80)   │
         └─────────────────────┘         │  tripchat-alb      │
                                         │  sticky sessions   │
                                         └─────────┬─────────┘
                                                   │
                                    ┌──────────────▼──────────────┐
                                    │       ECS Fargate             │
                                    │   tripchat-backend:8080       │
                                    │   Spring Boot                 │
                                    │   (public subnet, sg-backend) │
                                    └──────┬──────────┬────────────┘
                                           │          │
                              ┌────────────┘          └──────────────┐
                              │                                       │
              ┌───────────────▼────────┐         ┌───────────────────▼──┐
              │  RDS PostgreSQL 16     │         │  ElastiCache Redis 7  │
              │  tripchat-postgres     │         │  tripchat-redis        │
              │  (private subnet)      │         │  (private subnet)      │
              └────────────────────────┘         └──────────────────────┘
                                                          │
                                         ┌────────────────▼──────────────┐
                                         │   MSK Serverless Kafka         │
                                         │   chat.messages (3 partitions) │
                                         │   IAM auth, port 9098 TLS      │
                                         │   (private subnet)             │
                                         └───────────────────────────────┘

Secrets Manager ──► JWT_SECRET, DB_PASSWORD (injected at ECS task start)
ECR             ──► Docker image registry (<ACCOUNT_ID>.dkr.ecr.<region>)
CloudWatch Logs ──► /ecs/tripchat-backend (structured application logs)
```

---

## Infrastructure Resource Inventory

> Real values are kept privately. Replace `<ACCOUNT_ID>`, `<REGION>`, and resource-specific identifiers with your own when deploying.

| Resource | Pattern / Example |
|---|---|
| AWS Account | `<ACCOUNT_ID>` |
| Region | `us-east-1` |
| VPC | `vpc-<id>` |
| SG — ALB | `sg-<id>` (inbound 80, 443 from `0.0.0.0/0`) |
| SG — Backend | `sg-<id>` (inbound 8080 from sg-alb only) |
| SG — RDS | `sg-<id>` (inbound 5432 from sg-backend only) |
| SG — Redis | `sg-<id>` (inbound 6379 from sg-backend only) |
| SG — Kafka | `sg-<id>` (inbound 9098 from sg-backend only) |
| RDS endpoint | `tripchat-postgres.<id>.<region>.rds.amazonaws.com` |
| Redis endpoint | `tripchat-redis.<id>.0001.use1.cache.amazonaws.com` |
| MSK bootstrap | `boot-<id>.c2.kafka-serverless.<region>.amazonaws.com:9098` |
| MSK cluster ARN | `arn:aws:kafka:<region>:<ACCOUNT_ID>:cluster/tripchat-kafka/<id>` |
| ECR | `<ACCOUNT_ID>.dkr.ecr.<region>.amazonaws.com/tripchat-backend` |
| ECS Cluster | `tripchat` |
| ECS Service | `tripchat-backend` |
| Current Task Def | `tripchat-backend:<revision>` |
| ALB DNS | `tripchat-alb-<id>.<region>.elb.amazonaws.com` |
| ALB ARN | `arn:aws:elasticloadbalancing:<region>:<ACCOUNT_ID>:loadbalancer/app/tripchat-alb/<id>` |
| Target Group ARN | `arn:aws:elasticloadbalancing:<region>:<ACCOUNT_ID>:targetgroup/tripchat-backend-tg/<id>` |
| S3 Bucket | `tripchat-frontend-<ACCOUNT_ID>` |
| CloudFront ID | `<DISTRIBUTION_ID>` |
| CloudFront Domain | `<id>.cloudfront.net` |
| Secret — DB password | `arn:aws:secretsmanager:<region>:<ACCOUNT_ID>:secret:tripchat/db-password-<suffix>` |
| Secret — JWT secret | `arn:aws:secretsmanager:<region>:<ACCOUNT_ID>:secret:tripchat/jwt-secret-<suffix>` |
| ECS Execution Role | `tripchat-ecs-task-execution-role` |
| ECS Task Role | `tripchat-ecs-task-role` |

---

## Deployment Sequence

### Phase 1 — Networking
1. VPC with public + private subnets
2. Security groups with least-privilege inbound rules:
   - `sg-alb`: 80, 443 from `0.0.0.0/0`
   - `sg-backend`: 8080 from `sg-alb` only
   - `sg-rds`: 5432 from `sg-backend` only
   - `sg-redis`: 6379 from `sg-backend` only
   - `sg-kafka`: 9098 from `sg-backend` only

### Phase 2 — Data Services
3. RDS PostgreSQL 16 (`db.t3.micro`, private subnet, backup-retention 0 for free tier)
4. ElastiCache Redis 7 (`cache.t3.micro`, private subnet)
5. MSK Serverless Kafka (private subnet, IAM auth enabled)

### Phase 3 — Secrets
6. Store `tripchat/db-password` and `tripchat/jwt-secret` in Secrets Manager

### Phase 4 — Backend Container
7. Create ECR repository `tripchat-backend`
8. Build Docker image with `--platform linux/amd64` (critical — see Issues section)
9. Push to ECR with an explicit version tag
10. Create ECS cluster and task definition with Secrets Manager references
11. Create ECS service attached to ALB target group

### Phase 5 — Load Balancer
12. Create ALB (public subnets, `sg-alb`)
13. Create target group (type: IP, port 8080, health check: `/actuator/health`)
14. Enable stickiness (lb_cookie, 1-day duration)
15. HTTP :80 listener → forward to target group (HTTPS pending domain/cert)

### Phase 6 — Frontend
16. Create S3 bucket with public access blocked
17. Build React app: `npm run build`
18. Sync to S3: `aws s3 sync dist/ s3://tripchat-frontend-<ACCOUNT_ID> --delete`
19. Create CloudFront distribution:
    - S3 origin with OAC
    - Default root object: `index.html`
    - Custom error pages: 403 → `/index.html` (200), 404 → `/index.html` (200)
    - Cache behaviors: `/api/*` and `/ws*` → ALB origin (no cache, all methods)

---

## Code Changes Required for Production

### 1. `backend/src/main/resources/application-prod.yml`
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate        # never modify schema in prod
  datasource:
    hikari:
      maximum-pool-size: 20
  kafka:
    properties:
      "[security.protocol]": SASL_SSL
      "[sasl.mechanism]": AWS_MSK_IAM
      "[sasl.jaas.config]": "software.amazon.msk.auth.iam.IAMLoginModule required;"
      "[sasl.client.callback.handler.class]": software.amazon.msk.auth.iam.IAMClientCallbackHandler

cors:
  allowed-origins: ${ALLOWED_ORIGINS}

logging:
  level:
    com.tripchat: INFO
    org.hibernate.SQL: WARN
```

**Key design decisions:**
- Bracket notation `"[security.protocol]"` — required for YAML keys that contain dots; without brackets, Spring Boot nests them as `security → protocol` (wrong)
- `ddl-auto: validate` — Hibernate checks schema matches entities but never changes it. Schema changes go through migrations only. Use `update` only on the very first deploy to create tables.

### 2. `backend/src/main/java/com/tripchat/config/KafkaConfig.java`
- Inject `KafkaProperties` (Spring Boot's auto-resolved Kafka config bean)
- Merge `kafkaProperties.getProperties()` into the manual consumer props map — this flows SASL_SSL settings from `application-prod.yml` into the custom `ConsumerFactory`
- Add `NewTopic` bean for `chat.messages` — Spring Boot's `KafkaAdmin` creates the topic on MSK on startup

### 3. `backend/pom.xml`
- Add MSK IAM auth library:
```xml
<dependency>
    <groupId>software.amazon.msk</groupId>
    <artifactId>aws-msk-iam-auth</artifactId>
    <version>2.1.1</version>
</dependency>
```

### 4. `backend/src/main/java/com/tripchat/config/WebSocketConfig.java`
- Changed `setAllowedOriginPatterns("*")` → `setAllowedOriginPatterns("${cors.allowed-origins:*}")`
- Default `*` preserves local dev behavior; in prod reads from `ALLOWED_ORIGINS` env var

### 5. `frontend/.env.production`
```
VITE_API_BASE_URL=https://api.yourdomain.com
```
(Update with real domain once registered)

---

## Issues Faced & Fixes Applied

### Issue 1 — `exec format error` on ECS startup
**Symptom:** Container immediately exited with `exec format error`.
**Root cause:** Docker image was built on Apple Silicon (ARM64) but ECS Fargate uses AMD64.
**Fix:** Rebuild with `docker build --platform linux/amd64 ...`
**Lesson:** Always specify `--platform linux/amd64` when building for AWS on an M1/M2 Mac.

---

### Issue 2 — `Schema-validation: missing table [chat_groups]`
**Symptom:** App crashed on startup with Hibernate schema validation error.
**Root cause:** `application-prod.yml` sets `ddl-auto: validate`, but the database was empty (no tables created yet).
**Fix:** Override with env var `SPRING_JPA_HIBERNATE_DDL_AUTO=update` in the task definition for the first deploy to auto-create tables. Removed after schema was created.
**Lesson:** Always use `validate` in production long-term. `update` is only for bootstrapping. Consider adopting Flyway for proper schema migrations.

---

### Issue 3 — `DuplicateKeyException: found duplicate key spring`
**Symptom:** App crashed on startup with SnakeYAML parser error.
**Root cause:** `application-prod.yml` had two separate `spring:` top-level blocks — one for JPA/datasource settings added initially, another for Kafka properties added later. YAML does not allow duplicate keys at the same level.
**Fix:** Merged all settings under a single `spring:` block.
**Lesson:** YAML is strict about key uniqueness. When adding to an existing YAML key, always extend the existing block rather than creating a new one.

---

### Issue 4 — MSK connecting with `PLAINTEXT` instead of `SASL_SSL`
**Symptom:** Kafka consumer connected but immediately disconnected; logs showed `security.protocol = PLAINTEXT` instead of `SASL_SSL`.
**Root cause:** `KafkaConfig.java` built its own `props` map manually for the `ConsumerFactory`. This map never included the `spring.kafka.properties.*` entries from `application-prod.yml` because the code read `@Value("${spring.kafka.bootstrap-servers}")` directly — not via `KafkaProperties`. SASL/SSL settings were silently ignored.
**Fix:** Injected `KafkaProperties kafkaProperties` and added `props.putAll(kafkaProperties.getProperties())` to merge the SASL/SSL settings into the consumer factory config.
**Lesson:** When writing a custom `ConsumerFactory` alongside Spring Boot Kafka autoconfiguration, always inject `KafkaProperties` and merge `getProperties()` to capture profile-specific overrides.

---

### Issue 5 — `JsonDeserializer must be configured via property setters OR configuration properties, not both`
**Symptom:** App crashed on startup with Spring Kafka conflict error.
**Root cause:** Initial fix used `kafkaProperties.buildConsumerProperties(null)`, which includes Spring Boot's auto-configured deserializer class names (`spring.deserializer.value.delegate.class`, etc.). Our code also set an explicit `JsonDeserializer` instance — two conflicting deserializer configurations.
**Fix:** Switched from `buildConsumerProperties()` to `kafkaProperties.getProperties()`, which returns only the additional properties map (`spring.kafka.properties.*`) — no deserializer settings, no conflict.
**Lesson:** `getProperties()` = only `spring.kafka.properties.*` (safe to merge). `buildConsumerProperties()` = full consumer config including deserializer settings (conflicts with custom deserializer beans).

---

### Issue 6 — `UNKNOWN_TOPIC_OR_PARTITION` — Kafka consumer errors every second
**Symptom:** Consumer log spammed with `Error while fetching metadata: {chat.messages=UNKNOWN_TOPIC_OR_PARTITION}`. Register API returned 500.
**Root cause:** MSK Serverless does not auto-create topics. The `chat.messages` topic never existed.
**Fix:** Added a `@Bean NewTopic chatMessagesTopic()` in `KafkaConfig.java`. Spring Boot's auto-configured `KafkaAdmin` (which inherits the same SASL/SSL properties) creates the topic on MSK at application startup.
**Lesson:** MSK Serverless (and most production Kafka) has auto-topic-creation disabled. Always declare topics explicitly as `NewTopic` beans.

---

### Issue 7 — Sign In / Register buttons did nothing in production
**Symptom:** Clicking login or register form submit appeared to do nothing — user stayed on the same page.
**Root cause:** The axios base URL is `/api/v1` (relative). In production, this resolves to `https://<id>.cloudfront.net/api/v1/...`. CloudFront had no behavior for `/api/*`, so requests hit the S3 origin. S3 returned 403 (no such file). CloudFront custom error pages converted 403 → 200 with `index.html`. Axios treated the HTML response as a successful API response. `setAuth(undefined, undefined)` was called with the HTML string fields. Token was never set, so `ProtectedRoute` immediately redirected back to `/login` — appearing as if nothing happened.
**Fix:**
1. Added CloudFront cache behavior `/api/*` → ALB origin (no caching, all HTTP methods, `AllViewerExceptHostHeader` origin request policy)
2. Added CloudFront cache behavior `/ws*` → ALB origin (for WebSocket/STOMP connections)
3. Updated `ALLOWED_ORIGINS` ECS env var to include `https://<id>.cloudfront.net`
**Lesson:** A relative API base URL in a SPA requires either (a) a reverse proxy that routes `/api/*` to the backend, or (b) an absolute URL to the backend. CloudFront behaviors serve as the reverse proxy in production.

---

## Things to Handle Carefully

### 1. IAM Credentials in Chat — Rotate Immediately
The AWS access key used during this deployment was briefly exposed. It must be rotated:
```bash
aws iam create-access-key --user-name amitpatle10
# Update ~/.aws/credentials with new key
aws iam delete-access-key --user-name amitpatle10 --access-key-id <OLD_KEY>
```

### 2. `ddl-auto: validate` vs `update`
- **Never** run `ddl-auto: update` in steady-state production. It can silently drop columns or alter types on a schema mismatch.
- The current setup uses `validate` — any schema change requires a manual migration.
- **Long-term action:** Adopt Flyway. Add versioned SQL scripts in `src/main/resources/db/migration/`. Flyway runs migrations on startup and never runs the same script twice.

### 3. Docker Image Platform
- Always build with `--platform linux/amd64` on Apple Silicon (M1/M2/M3) Macs.
- ECS Fargate `X86_64` architecture only runs AMD64 images.
- A wrong-platform image fails silently with `exec format error` — no helpful error message in ECS.

### 4. Kafka Topic Creation
- MSK Serverless has auto-topic-creation disabled.
- All new Kafka topics must be declared as `@Bean NewTopic` in `KafkaConfig.java`.
- If a producer tries to publish to a non-existent topic, the app will not crash but will log `UNKNOWN_TOPIC_OR_PARTITION` and silently drop messages.

### 5. KafkaConfig — Merging SASL/SSL Settings
- Any custom `ConsumerFactory` or `ProducerFactory` must call `props.putAll(kafkaProperties.getProperties())`.
- Use `getProperties()` (only `spring.kafka.properties.*`) — **not** `buildConsumerProperties()` (which includes deserializer config and conflicts with explicit `JsonDeserializer` beans).

### 6. ALLOWED_ORIGINS Must Match Frontend Domain
- The Spring Boot CORS config reads from `ALLOWED_ORIGINS` env var in the ECS task definition.
- If the frontend domain changes (new CloudFront URL, custom domain, staging subdomain), the ECS task definition must be updated and redeployed.
- Current value: your CloudFront domain (`https://<id>.cloudfront.net` until a custom domain is set)
- Update when a real domain is registered.

### 7. ALB Sticky Sessions for WebSocket
- STOMP WebSocket sessions are stateful — a client subscribes to channels on a specific ECS task.
- Without sticky sessions, load balancer round-robin can send a reconnecting client to a different task with no subscription state, breaking real-time updates.
- Sticky sessions are set to `lb_cookie` with a 1-day duration. Do not disable this.

### 8. Secrets Manager — Never Put Credentials in Plain-Text Env Vars
- `DB_PASSWORD` and `JWT_SECRET` use the `secrets` block in the task definition (not `environment`).
- The `secrets` block injects values at task start time from Secrets Manager. They are never visible in ECS console, CloudTrail event history, or application logs.
- Adding credentials to `environment` would expose them in the ECS console and CloudTrail.

### 9. ECS Task Role vs Execution Role
- The **task role** (`tripchat-ecs-task-role`) provides MSK IAM credentials to the application code.
- The **execution role** (`tripchat-ecs-task-execution-role`) allows the ECS agent (not your code) to pull images from ECR and write logs to CloudWatch.
- Never merge these two roles. If you add new AWS service calls to application code, add permissions to the task role only.

### 10. CloudFront Behavior Order Matters
- CloudFront evaluates behaviors in order of path pattern specificity.
- `/api/*` and `/ws*` behaviors must be listed before the default behavior (`*` → S3).
- The default behavior (`*`) must always point to S3 for static asset serving.

---

## Cost Estimate

| Service | Resource | Est. Monthly Cost |
|---|---|---|
| ECS Fargate | 0.5 vCPU / 1 GB, ~730h/mo | ~$12 |
| RDS PostgreSQL 16 | db.t3.micro, 20 GB gp3 | ~$15 |
| ElastiCache Redis 7 | cache.t3.micro | ~$13 |
| MSK Serverless | pay-per-GB ingested/egested | ~$10–25 |
| ALB | 1 LCU baseline | ~$16 |
| CloudFront | ~10 GB transfer + requests | ~$2 |
| S3 | static assets (<1 GB) | ~$0.25 |
| ECR | ~500 MB stored | ~$0.50 |
| Secrets Manager | 2 secrets | ~$0.80 |
| CloudWatch Logs | ECS logs (~5 GB/mo) | ~$3 |
| **Total** | | **~$72–90/mo** |

> NAT Gateway (~$32/mo) avoided by placing ECS tasks in public subnets with security group restrictions.

---

## Pending Work

| # | Task | Priority |
|---|---|---|
| 1 | Register a domain via Route 53 Domains | High |
| 2 | Request ACM certificate for `yourdomain.com` and `*.yourdomain.com` in `us-east-1` | High |
| 3 | Add HTTPS :443 listener to ALB with ACM cert; redirect HTTP :80 → HTTPS | High |
| 4 | Create Route 53 hosted zone; add A Alias records for CloudFront and ALB | High |
| 5 | Update `ALLOWED_ORIGINS` ECS env var to real domain | High |
| 6 | Update `frontend/.env.production` `VITE_API_BASE_URL` to real API domain | High |
| 7 | Rebuild and redeploy frontend with real domain | High |
| 8 | Rotate the AWS access key shared during deployment | Critical |
| 9 | Set up GitHub Actions OIDC role and repo secrets (`AWS_DEPLOY_ROLE_ARN`, `CLOUDFRONT_DISTRIBUTION_ID`) | Medium |
| 10 | Adopt Flyway for schema migrations (replace `ddl-auto: update` bootstrap) | Medium |
| 11 | Enable RDS Multi-AZ for production HA | Low |
| 12 | Set up CloudWatch alarms (ECS CPU/memory, RDS connections, ALB 5xx rate) | Medium |
| 13 | Enable ElastiCache Redis in-transit encryption and AUTH token | Medium |
| 14 | Add auto-scaling policy for ECS service (target CPU 60%) | Low |
