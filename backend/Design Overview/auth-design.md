# TripChat — Auth System Design Overview

---

## What We Built

Two APIs:

- `POST /api/v1/auth/register` → creates a user, returns JWT
- `POST /api/v1/auth/login` → verifies credentials, returns JWT

---

## The Big Picture — How Classes Connect

```
HTTP Request
    │
    ▼
AuthController          ← receives the HTTP request, calls service
    │
    ▼
AuthService             ← thin orchestrator, delegates to strategy
    │
    ▼
AuthStrategy            ← interface (Strategy Pattern)
    │
    ▼
EmailPasswordAuthStrategy  ← actual logic lives here
    ├── UserRepository      ← talks to PostgreSQL
    ├── PasswordEncoder     ← BCrypt hash/verify
    ├── JwtService          ← generates JWT token
    └── AuthenticationManager ← Spring Security verifies credentials (login only)
```

---

## Class By Class — Simple Terms

### `AuthController`

- The front door. Receives HTTP requests, validates input, calls `AuthService`.
- Does **zero** business logic. Just routes the request.
- Returns `201 Created` for register, `200 OK` for login.

### `AuthService`

- The middleman. Receives the request from controller, passes it to the strategy.
- Knows **nothing** about how auth works — just delegates.
- Why it exists: if we add Google login later, we change the strategy, not this class.

### `AuthStrategy` (interface)

- A **contract** that defines what any auth method must do.
- Has two methods: `register()` and `login()`.
- Today: one implementation (`EmailPasswordAuthStrategy`).
- Tomorrow: add `GoogleAuthStrategy` without touching anything else.

### `EmailPasswordAuthStrategy`

- Where the real work happens.
- **Register flow:** check duplicates → hash password → save user → issue JWT
- **Login flow:** verify credentials via Spring Security → load user → issue JWT
- Has four dependencies: `UserRepository`, `PasswordEncoder`, `JwtService`, `AuthenticationManager`

### `JwtService`

- Knows how to create and read JWT tokens.
- `generateToken(user)` → builds a signed token with email, userId, username inside.
- `isTokenValid(token)` → checks if token is not expired and signature is correct.
- Uses HS256 algorithm with a secret key from `application.yml`.

### `JwtAuthFilter`

- Runs on **every request** before it reaches any controller.
- Reads the `Authorization: Bearer <token>` header.
- If valid token → loads the user, sets them as "authenticated" for this request.
- If no token or invalid → passes through (Spring Security rejects if endpoint needs auth).

### `SecurityConfig`

- Wires everything together for Spring Security.
- Says: `/api/v1/auth/**` is public, everything else needs a valid JWT.
- Creates the `BCryptPasswordEncoder` and `AuthenticationManager` beans.
- Disables CSRF (not needed — we use JWT, not cookies).

### `CustomUserDetailsService`

- The bridge between Spring Security and our database.
- Spring Security asks: _"load me the user with this email"_ → this class does it.
- Exists as a separate class to avoid a circular dependency (explained below).

### `UserRepository`

- Interface to PostgreSQL. We just define method names, Spring generates the SQL.
- Key methods: `existsByEmailIgnoreCase()`, `findByEmailIgnoreCase()`.
- Case-insensitive — `AMIT@test.com` and `amit@test.com` are treated as the same.

### `User` (entity)

- Maps to the `users` table in PostgreSQL.
- Key design: `username` is the unique identity handle, `displayName` is free text.
- Has `authProvider` field (`LOCAL` or `GOOGLE`) for future extensibility.
- Soft delete via `isActive` — never hard delete users.

### `GlobalExceptionHandler`

- Catches exceptions thrown anywhere and converts them to clean JSON responses.
- No try-catch in controllers or services — exceptions bubble up here.

---

## Register Flow — Step by Step

```
1. POST /api/v1/auth/register  { email, password, username, displayName }
         │
2. @Valid checks input
   → blank email?        400 Bad Request
   → invalid email?      400 Bad Request
   → weak password?      400 Bad Request
   → blank username?     400 Bad Request
         │
3. EmailPasswordAuthStrategy.register()
   → email already exists?     409 Conflict
   → username already taken?   409 Conflict
         │
4. BCrypt hashes the password
   → raw password is NEVER stored
         │
5. User entity saved to PostgreSQL
   → id (UUID), email (lowercase), username (lowercase),
     displayName, passwordHash, authProvider=LOCAL
         │
6. JwtService creates a signed JWT
   → contains: email, userId, username, expiry (24h)
         │
7. 201 Created
   { token, tokenType: "Bearer", expiresIn: 86400000, user: { id, email, username, displayName } }
```

---

## Login Flow — Step by Step

```
1. POST /api/v1/auth/login  { email, password }
         │
2. @Valid checks input
   → blank fields?   400 Bad Request
         │
3. EmailPasswordAuthStrategy.login()
         │
4. AuthenticationManager.authenticate(email, rawPassword)
   Spring Security internally:
     → loads user via CustomUserDetailsService
     → runs BCrypt.matches(rawPassword, storedHash)
     → checks isActive flag
         │
   ┌─────┴──────────────────────────────┐
   │ FAIL (any reason)                  │ SUCCESS
   ▼                                    ▼
   InvalidCredentialsException      authentication passes
   → 401 "Invalid email or password"
   (same message always — attacker
    cannot tell if email exists or
    password is wrong)
                                        │
                                   5. Load User from DB
                                        │
                                   6. JwtService creates JWT
                                        │
                                   7. 200 OK
                                      { token, user }
```

---

## How Classes Relate (Has-A Relationships)

```
AuthController
  └── has-a ──► AuthService

AuthService
  └── has-a ──► AuthStrategy (interface)
                    └── implemented by EmailPasswordAuthStrategy

EmailPasswordAuthStrategy
  ├── has-a ──► UserRepository
  ├── has-a ──► PasswordEncoder (BCrypt)
  ├── has-a ──► JwtService
  └── has-a ──► AuthenticationManager

JwtAuthFilter
  ├── has-a ──► JwtService
  └── has-a ──► UserDetailsService (CustomUserDetailsService)

SecurityConfig
  ├── has-a ──► JwtAuthFilter
  └── has-a ──► UserDetailsService (CustomUserDetailsService)

CustomUserDetailsService
  └── has-a ──► UserRepository

UserRepository
  └── manages ──► User entity

User
  └── has-a ──► AuthProvider enum (LOCAL | GOOGLE)

AuthResponse
  └── has-a ──► UserResponse
```

---

## Design Patterns Used

| Pattern                     | Where                                   | Why                                                                 |
| --------------------------- | --------------------------------------- | ------------------------------------------------------------------- |
| **Strategy**                | `AuthStrategy` interface                | Plug in Google login later without changing existing code           |
| **Chain of Responsibility** | `GlobalExceptionHandler`, Filter chain  | Each handler deals with one exception type, no try-catch everywhere |
| **Decorator (AOP)**         | `ExecutionTimeAspect`                   | Wraps every service method with timing — service has zero awareness |
| **Repository (DDD)**        | `UserRepository`                        | Service talks to an interface, not raw SQL or Hibernate             |
| **Static Factory**          | `UserResponse.from(User)`               | Mapping logic lives in the DTO, not scattered in services           |
| **Singleton**               | Spring beans (`@Service`, `@Component`) | One instance per bean, shared across all requests                   |

---

## The Circular Dependency Problem (And Fix)

**The problem:**

```
SecurityConfig  ──needs──► JwtAuthFilter
     ▲                           │
     │                           │ needs
     └──── was defining ──── UserDetailsService

Spring couldn't figure out which bean to create first. Infinite loop.
```

**The fix:**

```
CustomUserDetailsService  ← standalone class, no circular deps
         │                         │
         ▼                         ▼
   SecurityConfig           JwtAuthFilter
   (injects it)             (injects it)

Both now depend on CustomUserDetailsService.
CustomUserDetailsService depends on nothing in this chain.
Spring creates it first, then the other two. No cycle.
```

---

## Security Decisions

| Decision         | What We Do                                      | Why                                          |
| ---------------- | ----------------------------------------------- | -------------------------------------------- |
| Password storage | BCrypt hash only, never raw                     | Brute force resistant, one-way               |
| User enumeration | Same error for wrong password AND unknown email | Attacker can't tell which emails exist       |
| Token type       | JWT (stateless)                                 | No server-side session needed, scales easily |
| CSRF             | Disabled                                        | JWT in header, not cookies — no CSRF risk    |
| Session          | Stateless                                       | Each request is independently authenticated  |
| Soft delete      | `isActive = false`, never DELETE                | Preserves message history and audit trail    |

---

## DTOs — What Goes In, What Comes Out

```
RegisterRequest (IN)          LoginRequest (IN)
  email     → @Email            email     → @Email
  password  → 8+ chars          password  → @NotBlank
             1 digit                       (no rules — DB verifies)
             1 special char
  username  → alphanumeric+_
  display   → 2-30 chars

AuthResponse (OUT) — same for both register and login
  token       → JWT string
  tokenType   → "Bearer"
  expiresIn   → 86400000ms (24h)
  user
    id          → UUID
    email
    username
    displayName
    (passwordHash is NEVER in the response)
```

---

## What's Not In The Token

The JWT contains `email`, `userId`, `username`. It does **not** contain:

- `passwordHash` — never
- `isActive` — checked fresh from DB on each login attempt
- `authProvider` — not needed client-side
- `displayName` — can change, better fetched fresh

---

## File Map

```
src/main/java/com/tripchat/
│
├── controller/
│   └── AuthController.java           ← POST /register, POST /login
│
├── service/
│   └── AuthService.java              ← delegates to AuthStrategy
│
├── security/
│   ├── strategy/
│   │   ├── AuthStrategy.java         ← interface (register + login)
│   │   └── EmailPasswordAuthStrategy.java  ← current implementation
│   ├── jwt/
│   │   ├── JwtService.java           ← generate + validate tokens
│   │   └── JwtAuthFilter.java        ← validates JWT on every request
│   ├── CustomUserDetailsService.java ← loads user from DB for Spring Security
│   └── SecurityConfig.java           ← wires everything, sets auth rules
│
├── repository/
│   └── UserRepository.java           ← DB access for User
│
├── model/
│   ├── User.java                     ← users table entity
│   └── enums/AuthProvider.java       ← LOCAL | GOOGLE
│
├── dto/
│   ├── request/
│   │   ├── RegisterRequest.java      ← input + validation rules
│   │   └── LoginRequest.java         ← input + validation rules
│   └── response/
│       ├── AuthResponse.java         ← token + user info
│       └── UserResponse.java         ← safe user representation
│
├── exception/
│   ├── GlobalExceptionHandler.java   ← central error handling
│   ├── EmailAlreadyExistsException.java    ← 409
│   ├── UsernameAlreadyTakenException.java  ← 409
│   └── InvalidCredentialsException.java   ← 401
│
└── config/
    ├── ExecutionTimeAspect.java      ← logs execution time on all service methods
    └── JpaConfig.java                ← enables @CreatedDate / @LastModifiedDate
```
