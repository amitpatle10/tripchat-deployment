# TripChat Frontend

React + TypeScript frontend for TripChat — a real-time group chat application.

## Prerequisites

- Node.js 20+
- Backend running at `http://localhost:8080` (see `../backend/README.md`)

## Setup

```bash
npm install
```

## Development

```bash
npm run dev
```

Opens at `http://localhost:5173`. API calls to `/api/*` and WebSocket connections to `/ws` are proxied to `localhost:8080`.

## Tests

```bash
# Run all tests once
npx vitest run

# Run a single test file
npx vitest run src/features/auth/components/LoginForm.test.tsx

# Watch mode
npx vitest
```

## Other Commands

```bash
# Type check (no emit)
npx tsc --noEmit

# Production build
npm run build

# Preview production build
npm run preview
```
