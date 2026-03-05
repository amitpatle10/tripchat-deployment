# TripChat Frontend — Base Architecture

> Covers the foundational tech stack decisions made before writing a single feature.
> Each section: what we chose, why, what we rejected, and likely interview questions.

---

## Table of Contents

1. [Build Tool — Vite vs Webpack vs CRA](#1-build-tool--vite-vs-webpack-vs-cra)
2. [TypeScript at API Boundaries](#2-typescript-at-api-boundaries)
3. [State Management — The Three-Layer Model](#3-state-management--the-three-layer-model)
4. [Zustand — Deep Dive](#4-zustand--deep-dive)
5. [TanStack Query — Deep Dive](#5-tanstack-query--deep-dive)
6. [Axios Interceptors](#6-axios-interceptors)
7. [React Router v6 — Key Patterns](#7-react-router-v6--key-patterns)
8. [WebSocket + STOMP Architecture](#8-websocket--stomp-architecture)
9. [Feature-based Project Structure](#9-feature-based-project-structure)
10. [Key Design Patterns Reference](#10-key-design-patterns-reference)

---

## 1. Build Tool — Vite vs Webpack vs CRA

**What Vite does differently:**
Traditional bundlers like Webpack process your entire codebase on startup — they bundle everything before the dev server is ready. Vite skips this. It serves files as native ES Modules directly to the browser. The browser requests a file, Vite transforms and sends it on demand. Result: instant dev server start regardless of project size.

**HMR (Hot Module Replacement):**
When you edit a file, only that module is swapped — not the whole bundle. Vite's HMR is faster because it leverages the ES module graph to know exactly what changed and what depends on it.

**Why not CRA:**
Create React App uses Webpack internally. It is slow (30+ second cold starts on large projects), no longer maintained by Meta, and cannot be configured without ejecting (which is irreversible). The ecosystem has moved to Vite.

**Interview questions:**

- _What is the difference between a bundler and a dev server?_ — A bundler combines modules into deployable files. A dev server serves them for development. Vite separates these concerns; in dev it serves without bundling, in production it bundles with Rollup.
- _What are ES Modules?_ — The browser-native module system (`import`/`export`). Vite uses this directly in dev, so there is no transform step on every file change.
- _What is tree shaking?_ — Dead code elimination during the build. Only code that is actually imported ends up in the bundle.

---

## 2. TypeScript at API Boundaries

**The core value:**
TypeScript catches errors at compile time that would otherwise surface at runtime in front of users. For a frontend consuming a REST API, the highest-risk boundary is the JSON response — you assume a shape, but the server can change it, return null where you did not expect it, or add/remove fields.

**The deleted-user problem:**
In TripChat, `senderId`, `senderUsername`, `senderDisplayName` are all `string | null` when a user is deleted. Without TypeScript you would write:

```ts
<p>{message.senderDisplayName}</p> // renders "null" or crashes
```

With TypeScript, `string | null` forces you to handle the null case before the code compiles. The type system is your safety net at the boundary where JSON enters your app.

**`z.infer<typeof schema>`:**
Zod lets you define a validation schema and infer the TypeScript type from it simultaneously — one source of truth for both runtime validation and compile-time types. No duplication between "what shape is this?" and "what type is this?".

**Interview questions:**

- _What is the difference between `interface` and `type` in TypeScript?_ — Both can describe object shapes. `interface` is extendable (can be merged and extended). `type` is more flexible (can represent unions, intersections, primitives). Prefer `interface` for API shapes that might be extended, `type` for unions like `'ADMIN' | 'MEMBER'`.
- _What are generics?_ — Type variables. `Array<T>` means "array of some type T". We use this when writing utility functions that work on multiple types — e.g., `useQuery<GroupResponse[]>` tells TanStack Query what type the query returns.
- _What is `unknown` vs `any`?_ — `any` disables type checking entirely. `unknown` forces you to narrow the type before using it. The Axios error interceptor uses `unknown` — we check `axios.isAxiosError(error)` before accessing `.response` safely.

---

## 3. State Management — The Three-Layer Model

**The fundamental mistake:** Most apps put everything in one global store. This conflates three completely different problems.

**Layer 1 — Local UI state (`useState`)**
Belongs to one component. No other component cares. Modal open/close, input focus, form field value. Lifting this to a global store is over-engineering.

**Layer 2 — Global client state (Zustand)**
Exists in memory for the session. Does not live on the server. Auth token, active group, WebSocket connection status, typing indicators. This is what your app "knows" independently of the server.

**Layer 3 — Server state (TanStack Query)**
Lives on the server. You fetch it, it can go stale, it needs to be invalidated when you mutate. Group list, message history, presence. TanStack Query caches it, refetches it in the background, tracks loading/error states — things you would have to hand-roll with Zustand.

**Why not Redux:**
Redux requires: define an action → define a reducer → dispatch the action → select the result. Four steps to update a value. Zustand does it in one. Redux is powerful for very large teams with complex state interactions — at TripChat's scale it is ceremony with no return.

**Why not React Context for global state:**
Context has a critical performance flaw — every component that calls `useContext(MyContext)` re-renders whenever _any_ value in that context changes. If you put `{ user, token, wsStatus, typingUsers }` in one context, updating `typingUsers` (which happens constantly in a chat app) re-renders every component subscribed to auth state. This is the cascading re-render problem.

**Interview questions:**

- _What is prop drilling?_ — Passing props through intermediate components that do not use them, only to get the data to a deeply nested child. Global state solves this.
- _What is lifting state up?_ — Moving state to the closest common ancestor of components that share it. The right pattern before reaching for global state.
- _When would you use Context over Zustand?_ — Context is appropriate for values that change rarely and are consumed widely: theme (light/dark), locale, feature flags. Not for frequently-updated state like chat messages or presence.

---

## 4. Zustand — Deep Dive

**Selector pattern:**

```ts
// ❌ subscribes to the entire store — re-renders on any change
const store = useAuthStore()

// ✅ subscribes only to token — re-renders only when token changes
const token = useAuthStore((s) => s.token)
```

This is the most important Zustand performance pattern. A component that only needs `token` should never re-render because `user.displayName` changed.

**`persist` middleware:**
Wraps the store with `localStorage` read/write automatically. On first render, Zustand rehydrates from `localStorage` — the user stays logged in after a page refresh without any manual code. The stored key is `tripchat-auth`.

**Accessing store outside React:**

```ts
useAuthStore.getState().token      // read
useAuthStore.getState().clearAuth() // write
```

`getState()` is the escape hatch for code that runs outside the component tree — Axios interceptors, STOMP client callbacks, WebSocket message handlers. This is what makes Zustand work as the single source of truth across the entire app, not just React components.

**Interview questions:**

- _What is a selector in state management?_ — A function that extracts a specific piece of state. Components subscribe only to what they select, so they only re-render when that specific piece changes.
- _What is the difference between Zustand and Redux?_ — Zustand is unopinionated and minimal. No actions, no reducers, no dispatch. Redux enforces a strict unidirectional data flow with actions and reducers, which helps with predictability in large teams but adds boilerplate.
- _How do you persist state across page refreshes in React?_ — `localStorage` or `sessionStorage` manually, or a persistence middleware like Zustand's `persist`.

---

## 5. TanStack Query — Deep Dive

**Why not `useState` + `useEffect` for fetching:**

```ts
// The naive approach — repeated boilerplate for every fetch
const [groups, setGroups] = useState([])
const [loading, setLoading] = useState(false)
const [error, setError] = useState(null)

useEffect(() => {
  setLoading(true)
  fetchGroups()
    .then(setGroups)
    .catch(setError)
    .finally(() => setLoading(false))
}, [])
```

This gives you no caching, no deduplication, no background refresh, no stale-while-revalidate. If two components both need the group list, you make two requests. TanStack Query solves all of this.

**`staleTime` vs `gcTime`:**

- `staleTime` (1 min in our config) — how long data is considered fresh. During this window, no background refetch happens. After it expires, the next component mount triggers a background refetch while showing cached data immediately.
- `gcTime` (5 min) — how long unused cache entries stay in memory after all subscribers unmount. This is not about freshness — it is about memory management.

**`queryKey` design:**

```ts
['groups']                    // all groups
['groups', groupId]           // one group by id
['messages', groupId]         // messages for a group
```

The key is an array. TanStack Query uses it for cache lookup and invalidation. `queryClient.invalidateQueries({ queryKey: ['groups'] })` invalidates all queries whose key starts with `'groups'`.

**`retry: 0` on mutations:**
If `POST /api/v1/groups` fails silently and retries, you could create duplicate groups. Mutations need user-visible feedback, not silent retries.

**Interview questions:**

- _What is stale-while-revalidate?_ — A caching strategy where stale data is served immediately (fast UX) while a fresh fetch happens in the background. The UI updates when fresh data arrives.
- _How do you invalidate cache after a mutation?_ — `queryClient.invalidateQueries({ queryKey: ['groups'] })` in the `onSuccess` of a `useMutation`. This causes the group list to refetch automatically.
- _What is the difference between `isLoading` and `isFetching` in TanStack Query?_ — `isLoading` is true only on the first load when there is no cached data. `isFetching` is true any time a request is in flight, including background refetches. Use `isLoading` for skeletons, `isFetching` for a subtle refresh indicator.

---

## 6. Axios Interceptors

**Request interceptor — JWT injection:**
Instead of passing the token in every API call, one interceptor reads it from Zustand and attaches it to every outgoing request. Single responsibility — API functions focus on their endpoint, not auth headers.

**Response interceptor — 401 handling:**
Token expiry is a cross-cutting concern. Without an interceptor, every API call would need:

```ts
if (response.status === 401) {
  clearAuth()
  navigate('/login')
}
```

With one interceptor, this happens once. Every 401 in the app is handled consistently.

**The outside-React navigation problem:**
`useNavigate()` is a React hook — it can only be called inside components. The interceptor runs outside React entirely. Solution: `createBrowserRouter` returns a `router` object with a `.navigate()` method callable anywhere. This is the React Router v6 supported pattern for exactly this use case.

**Interview questions:**

- _What is an interceptor?_ — Middleware that runs on every request or response before your code handles it. Equivalent to a filter in Spring Boot.
- _How do you handle JWT refresh in a React app?_ — In an interceptor: if a 401 arrives and there is a refresh token, call the refresh endpoint, update the stored token, and retry the original request. In TripChat Phase 1 there is no refresh endpoint, so we redirect to login.
- _What is the difference between `axios.create()` and importing axios directly?_ — `axios.create()` creates an instance with its own config and interceptors. Importing directly uses the global axios instance. Always use `create()` — it lets you have different configurations for different backends if needed.

---

## 7. React Router v6 — Key Patterns

**`createBrowserRouter` vs `BrowserRouter`:**
`createBrowserRouter` is the modern API (v6.4+). It returns a router object with a `.navigate()` method accessible outside React. `BrowserRouter` is the legacy component-based wrapper — no escape hatch for outside-React navigation.

**`ProtectedRoute` using `Outlet`:**

```tsx
function ProtectedRoute() {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <Outlet /> // renders the matched child route
}
```

`Outlet` is a placeholder that renders whatever child route matched. One `ProtectedRoute` guards all authenticated routes — add a new authenticated page and it is automatically protected.

**`replace` on redirect:**
`<Navigate to="/login" replace />` replaces the current history entry instead of pushing a new one. Without `replace`, pressing the browser back button from `/login` goes back to the protected page triggering another redirect loop. With `replace`, the history entry is overwritten — the loop is broken.

**Interview questions:**

- _What is the difference between `<Navigate>` and `useNavigate()`?_ — `<Navigate>` is a component used in JSX (good for redirect during render). `useNavigate()` is a hook returning an imperative function (good for navigation in event handlers and effects).
- _What is an Outlet in React Router?_ — A component that renders the matched child route. Used in layout routes and guard routes. Think of it as a slot.
- _How do you pass data to a redirected route?_ — Via `state`: `navigate('/login', { state: { from: location } })`. The login page reads `location.state.from` to redirect back after login.

---

## 8. WebSocket + STOMP Architecture

**Why STOMP over raw WebSocket:**
Raw WebSocket sends and receives plain strings. STOMP is a protocol layered on top — it adds destinations (like URL routes for messages), subscriptions, headers, heartbeats, and acknowledgements. Spring Boot's WebSocket support is STOMP-native. Without `@stomp/stompjs`, you would have to implement the STOMP framing protocol manually.

**Why SockJS:**
Some corporate networks and proxies block WebSocket upgrades. SockJS provides a fallback — if WebSocket fails, it transparently falls back to HTTP long-polling. Your app code does not change. Spring Boot's `/ws` endpoint is configured to accept SockJS connections.

**Module singleton pattern:**
One `Client` instance for the entire session. If the client lived inside a React component or hook, navigating away would unmount the component and disconnect the WebSocket. The module singleton survives navigation — the connection is tied to the app session, not a component's lifetime.

**The `wsConnected` bridge:**
STOMP fires `onConnect` outside React. React's rendering system does not know about it. Zustand's `setWsConnected(true)` bridges this: it triggers a state update that React can observe. Every `useStompSubscription` has `wsConnected` in its dependency array — when the connection is established or re-established after a drop, all subscriptions reactivate automatically. This is how reconnect is free.

**The `onMessageRef` pattern:**

```ts
const onMessageRef = useRef(onMessage)
onMessageRef.current = onMessage // always up to date

useEffect(() => {
  const sub = stompClient.subscribe(destination, (frame) => {
    onMessageRef.current(frame) // calls latest version
  })
  return () => sub.unsubscribe()
}, [wsConnected, destination]) // callback NOT in deps
```

If `onMessage` were in the dependency array and the caller passes an inline function, every render would unsubscribe and resubscribe — destroying and recreating the STOMP subscription on every keystroke. The ref stores the latest version of the callback without it being a dependency.

**Interview questions:**

- _What is the difference between WebSocket and HTTP?_ — HTTP is request-response: client initiates, server responds, connection closes. WebSocket is bidirectional: one persistent connection, both sides can send at any time. Chat requires the server to push messages to clients without the client requesting them.
- _What is STOMP?_ — Simple Text Oriented Messaging Protocol. A messaging protocol that runs over WebSocket, similar to how HTTP runs over TCP. Adds destinations, subscriptions, and headers on top of raw WebSocket frames.
- _How do you prevent stale closures in `useEffect`?_ — Use a ref to hold the latest value of a variable that changes frequently. The effect captures the ref (stable reference), and `ref.current` always points to the latest value.

---

## 9. Feature-based Project Structure

**The rule:**

> If a file is used by one feature → it lives inside that feature's folder.
> If it is used by two or more features → it moves to a top-level shared directory.

**Why not layer-based:**
Layer-based structure (`components/`, `hooks/`, `api/` at the top level) puts files together by what they _are_, not by what they _do together_. To work on the chat feature you would navigate between `components/MessageBubble.tsx`, `hooks/useMessages.ts`, `api/messages.ts`, `store/chatSlice.ts` — four different directories for one feature. With feature-based structure, everything for chat is in `features/chat/`.

**The cohesion principle:**
Files that change together should live together. When you add a new message feature, you touch `features/chat/` — not four separate directories.

**Our structure:**

```
src/
├── features/
│   ├── auth/        # Login, Register — components, hooks, api
│   ├── groups/      # Group list, modals — components, hooks, api
│   └── chat/        # Messages, typing, presence — components, hooks, api
├── components/      # Shared UI primitives used by 2+ features
├── store/           # Zustand stores (cross-feature global state)
├── types/           # Shared TypeScript interfaces from API_CONTRACTS.md
├── lib/             # axios, queryClient, stompClient, router
└── main.tsx
```

**Interview questions:**

- _What is cohesion in software design?_ — How strongly related the elements within a module are. High cohesion means a module does one thing and all its parts serve that purpose. The feature folder is a high-cohesion unit.
- _What is the difference between cohesion and coupling?_ — Cohesion is about relatedness within a module. Coupling is about dependency between modules. Good design: high cohesion, low coupling.
- _How do you decide when a component should be in `components/` vs a feature folder?_ — One question: "Is this used by more than one feature?" If yes → `components/`. If no → keep it in the feature. Move to shared only when actual reuse exists, not anticipated reuse.

---

## 10. Key Design Patterns Reference

| Pattern                     | Where                                          | What it solves                                                                                   |
| --------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Singleton**               | `stompClient`, `axiosInstance`, `queryClient`  | One instance shared across the app — consistent state, no duplication                            |
| **Facade**                  | `src/lib/axios.ts`                             | Hides JWT injection and 401 handling behind a clean `api` object                                 |
| **Observer**                | Zustand selectors, STOMP subscriptions         | Components react to state changes without being tightly coupled to the source                    |
| **Ref as stable callback**  | `onMessageRef` in `useStompSubscription`       | Decouples effect dependencies from frequently-changing callback references                       |
| **Guard / ProtectedRoute**  | `src/lib/router.tsx`                           | Centralises auth check — one place, all protected routes covered                                 |
| **Proxy (Vite dev server)** | `vite.config.ts` server.proxy                  | Frontend calls `/api/*` locally, Vite forwards to `localhost:8080` — no CORS, no hardcoded URLs  |
