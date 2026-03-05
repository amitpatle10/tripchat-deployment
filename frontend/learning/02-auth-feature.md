# TripChat Frontend — Auth Feature

> Everything learned while building login, registration, and session management.
> Covers the full login flow end-to-end, the auth store, form validation, and route protection.

---

## Table of Contents

1. [The Auth Flow — End to End](#1-the-auth-flow--end-to-end)
2. [Zod Schemas — Validation as the Single Source of Truth](#2-zod-schemas--validation-as-the-single-source-of-truth)
3. [React Hook Form — Why Uncontrolled Inputs Win](#3-react-hook-form--why-uncontrolled-inputs-win)
4. [useMutation for Login and Register](#4-usemutation-for-login-and-register)
5. [authStore — Zustand with Persistence](#5-authstore--zustand-with-persistence)
6. [Connecting the WebSocket on Login](#6-connecting-the-websocket-on-login)
7. [ProtectedRoute — Guarding Authenticated Pages](#7-protectedroute--guarding-authenticated-pages)
8. [Error Handling — Client vs Server Errors](#8-error-handling--client-vs-server-errors)

---

## 1. The Auth Flow — End to End

Understanding the entire login sequence in order prevents bugs caused by out-of-order operations.

```
User submits login form
        ↓
React Hook Form validates (Zod) — catches empty/invalid fields before network
        ↓
POST /api/v1/auth/login  →  { token, user }
        ↓
setAuth(user, token)     →  Zustand stores both, persist writes to localStorage
        ↓
connectStomp(token)      →  STOMP CONNECT frame sent with JWT in header
        ↓
navigate('/', replace)   →  ProtectedRoute now sees token, renders GroupListPage
```

**Why this order matters:**
`setAuth` must run before `navigate`. The router evaluates `ProtectedRoute` synchronously on navigation. If you navigate first and set auth second, `ProtectedRoute` sees no token and redirects back to `/login` — a one-frame flicker or a redirect loop.

**Why `connectStomp` is called here:**
The WebSocket session needs a valid JWT in its CONNECT frame. That JWT is only available after a successful login. Connecting earlier (e.g. on app mount) would send an empty or missing token. Connecting later (e.g. inside GroupListPage) would mean the WebSocket is not ready when the page first loads.

**Interview questions:**

- _Why do you set auth state before navigating?_ — Because the route guard (ProtectedRoute) checks the store synchronously during the render triggered by navigation. If auth state isn't set yet, the guard redirects back to login.
- _Why open the WebSocket on login instead of on app start?_ — The WebSocket CONNECT frame must carry a valid JWT. You don't have a JWT until after a successful login. Attempting to connect before login would be unauthenticated.
- _What does `navigate('/', { replace: true })` do?_ — Navigates to the home page and replaces the current history entry (login) so the back button skips login, preventing a redirect loop.

---

## 2. Zod Schemas — Validation as the Single Source of Truth

**The problem Zod solves:**
Without Zod, you write validation logic twice — once in code, once as a TypeScript type. They can drift apart.

```ts
// Without Zod — two separate definitions that can contradict each other
function validateEmail(v: string) { return v.includes('@') }
interface LoginFormData { email: string; password: string }

// With Zod — one definition, type is derived automatically
const loginSchema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
})
type LoginFormData = z.infer<typeof loginSchema>  // derived, not hand-written
```

**Auth schemas in TripChat:**

```ts
// Login — minimal rules (server enforces credentials, not us)
const loginSchema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
})

// Register — mirror the backend's validation rules exactly
const registerSchema = z.object({
  displayName: z.string().min(2).max(30),
  username:    z.string().min(3).max(50).regex(/^[a-zA-Z0-9_]+$/),
  email:       z.string().email(),
  password:    z.string().min(8).regex(/[0-9]/).regex(/[@$!%*?&]/),
})
```

**Why login has looser rules than register:**
Login only needs to know if the fields are non-empty and email-shaped. The server validates credentials. Showing "password must have a special character" on the login page is confusing and exposes policy to attackers. Register rules mirror the backend exactly so the user never hits a backend 400 for something the client could have caught.

**Interview questions:**

- _What is `z.infer<typeof schema>`?_ — A TypeScript utility that reads a Zod schema and produces its corresponding TypeScript type. One definition serves both purposes — no duplication.
- _What is runtime validation vs compile-time validation?_ — TypeScript types are erased at runtime — they only exist during development. Zod runs at runtime (in the browser) and actually checks the values. Both are needed: TypeScript for developer experience, Zod for real input validation.
- _Why mirror backend validation rules in the frontend?_ — To give the user immediate feedback without a network round-trip. If the backend rejects a password missing a digit, the user finds out instantly from the frontend instead of waiting for a 400 response.

---

## 3. React Hook Form — Why Uncontrolled Inputs Win

**Controlled input (the default React pattern):**

```tsx
const [email, setEmail] = useState('')
<input value={email} onChange={(e) => setEmail(e.target.value)} />
```

Every keystroke → `setEmail` → state update → component re-render. On a 4-field form, typing one character causes 4 renders (React re-renders the whole component). On a 10-field form, 10 renders per keystroke.

**Uncontrolled input (React Hook Form's approach):**

```tsx
const { register } = useForm()
<input {...register('email')} />
```

The DOM owns the value. React Hook Form reads it via a `ref` only when needed (on submit or on blur). Zero re-renders while typing.

**What `register` returns:**
`register('fieldName')` returns `{ name, ref, onChange, onBlur }`. The `ref` connects the DOM input to RHF's internal tracker. `onChange` and `onBlur` trigger validation at the right moments. Spreading it on the input wires everything up.

**`handleSubmit`:**
Wraps your `onSubmit` handler. On submit: runs Zod validation via `zodResolver`, calls your handler only if valid, prevents the form from submitting if invalid. You never manually call `schema.parse()` — `zodResolver` does it for you.

**The `formState.errors` object:**
Keyed by field name. `errors.email?.message` is the string from your Zod schema. Renders only when the field has been touched or the form has been submitted — no error shown on first render.

**Interview questions:**

- _What is the difference between controlled and uncontrolled inputs?_ — Controlled: React state drives the value, re-renders on every change. Uncontrolled: the DOM owns the value, read via ref. React Hook Form uses uncontrolled for performance.
- _What does `noValidate` on a `<form>` tag do?_ — Disables the browser's built-in HTML5 validation popups. Lets you fully control validation — in our case, Zod handles it.
- _How does `zodResolver` work?_ — It's an adapter between React Hook Form's validation API and Zod. On submit (and optionally on blur), it runs `schema.safeParse(formValues)` and maps errors into RHF's expected shape.

---

## 4. useMutation for Login and Register

**Why `useMutation` and not a plain `async` function:**
`useMutation` gives you `isPending`, `isError`, and lifecycle hooks (`onSuccess`, `onError`) for free. Without it, you'd manage these states manually with `useState` — the same boilerplate TanStack Query exists to eliminate.

**`useLogin` — what happens on success:**

```ts
return useMutation({
  mutationFn: authApi.login,
  onSuccess: (data) => {
    setAuth(data.user, data.token)   // 1. store token — ProtectedRoute unblocks
    connectStomp(data.token)         // 2. open WebSocket with JWT
    navigate('/', { replace: true }) // 3. redirect
  },
})
```

`onSuccess` receives the server's response (`AuthResponse`) directly — no `.then()` chaining in the component. The component only calls `mutate(formData)` and handles `onError` for UI feedback.

**Keeping error handling in the component, not the hook:**
`onSuccess` lives in the hook because it's always the same (navigate to home). `onError` lives in the form component because the error message depends on context — 401 on login means "wrong password", 409 on register means "email taken". The form uses `setError('root', { message: '...' })` to show it in the error banner.

```ts
// In LoginForm.tsx — error handling stays in the presenter
login(data, {
  onError: (error) => {
    const is401 = axios.isAxiosError(error) && error.response?.status === 401
    setError('root', {
      message: is401 ? 'Invalid email or password' : 'Something went wrong.'
    })
  }
})
```

**`isPending` for the submit button:**

```tsx
<button disabled={isPending}>
  {isPending ? 'Signing in...' : 'Sign in'}
</button>
```

Prevents double-submission. The button is disabled while the request is in flight.

**Interview questions:**

- _What is `useMutation` in TanStack Query?_ — A hook for write operations (POST, PUT, DELETE). Manages pending/error/success state and provides lifecycle callbacks. The write equivalent of `useQuery`.
- _What is the difference between `mutate` and `mutateAsync`?_ — `mutate` is fire-and-forget — errors are caught by TanStack Query and surfaced in `onError`. `mutateAsync` returns a Promise you can `await` and must catch yourself with `try/catch`.
- _Why keep `onError` in the component instead of the hook?_ — Because the error message is a UI concern. The hook knows the operation failed; the component knows how to tell the user what it means in context.

---

## 5. authStore — Zustand with Persistence

**What the store holds:**

```ts
interface AuthState {
  user: UserResponse | null   // display name, username, id
  token: string | null        // JWT — sent on every API request
  setAuth: (user, token) => void
  clearAuth: () => void
}
```

`null` means "not logged in." Both `user` and `token` are set together on login and cleared together on logout or 401.

**`persist` middleware — how it works:**

```ts
create<AuthState>()(
  persist(
    (set) => ({ ... }),
    { name: 'tripchat-auth' }  // localStorage key
  )
)
```

On first call: Zustand reads `localStorage.getItem('tripchat-auth')`, parses the JSON, and merges it into the initial store state. On every `set` call: Zustand serializes the new state and writes it back. You write zero manual localStorage code.

**Why this store lives in `store/` and not `features/auth/`:**
The `authStore` is used by more than the auth feature:
- `lib/axios.ts` reads the token for every request
- `lib/stompClient.ts` reads it to connect the WebSocket
- `lib/router.tsx` reads it in `ProtectedRoute`

By the feature folder rule: used by multiple → goes to `store/`.

**`clearAuth` on logout:**
Clears both `user` and `token` in one call. `ProtectedRoute` sees `token === null` on the next render and redirects to `/login` automatically. No manual navigation needed for logout.

**Interview questions:**

- _How does Zustand's `persist` middleware work?_ — It wraps the store, reads from `localStorage` on initialization to rehydrate state, and writes back to `localStorage` on every state change. The key in `localStorage` is configurable.
- _What happens to Zustand state on a page refresh without `persist`?_ — It resets to the initial value. The user would be logged out. With `persist`, the token and user survive the refresh.
- _Why store `user` in Zustand alongside `token`?_ — Because components need display info (username, displayName) without making a separate `/me` API call on every mount. The user object is part of the login response and cheaply stored.

---

## 6. Connecting the WebSocket on Login

**`connectStomp` — what it does:**

```ts
export function connectStomp(token: string) {
  stompClient.configure({
    connectHeaders: { Authorization: `Bearer ${token}` },
  })
  stompClient.activate()
}
```

`configure` injects the JWT into the STOMP CONNECT frame. Spring Boot reads this header to authenticate the WebSocket session — same mechanism as HTTP Authorization header, but for WebSocket. `activate()` opens the connection.

**Why the token is passed as a parameter instead of read from the store:**
The store might not have flushed when `connectStomp` is called (Zustand's `persist` write is synchronous, but this is defensive). More importantly, it makes `connectStomp` a pure function that is easy to test — pass the token in, no hidden store read.

**The heartbeat — staying online:**

```ts
onConnect: () => {
  heartbeatInterval = setInterval(() => {
    stompClient.publish({ destination: '/app/presence/heartbeat' })
  }, 20_000)
}
```

The server marks users online in Redis with a 30-second TTL. The heartbeat fires every 20 seconds to reset the TTL. If the tab closes or the network drops, the heartbeat stops and the user appears offline after ~30 seconds. No explicit "user left" message needed — the TTL handles it automatically.

**Interview questions:**

- _Why does the STOMP CONNECT frame need the JWT?_ — HTTP headers travel with every HTTP request, but a WebSocket upgrade is a one-time HTTP request followed by a persistent raw connection. After the upgrade, there are no HTTP headers. The STOMP CONNECT frame is the only opportunity to authenticate the session.
- _What is a dead man's switch in the context of presence?_ — A pattern where a process must actively signal "I am alive" on a timer. If it stops signalling, it is presumed dead. The heartbeat is a dead man's switch — if the client stops sending heartbeats, the server marks the user offline after the TTL expires.
- _What happens to the WebSocket connection if the user closes the tab?_ — `stompClient.deactivate()` is not called (there is no `beforeunload` handler). The server simply stops receiving heartbeats, and the presence TTL expires naturally.

---

## 7. ProtectedRoute — Guarding Authenticated Pages

**The full picture:**

```tsx
// src/lib/router.tsx
function ProtectedRoute() {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <Outlet />
}

export const router = createBrowserRouter([
  { path: '/login',    element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    element: <ProtectedRoute />,   // wraps all authenticated routes
    children: [
      { path: '/',               element: <GroupListPage /> },
      { path: '/groups/:groupId', element: <ChatPage /> },
    ],
  },
])
```

**How `Outlet` works:**
`ProtectedRoute` renders `<Outlet />` which is a slot — React Router fills it with whichever child route matched the current URL. Add a new protected route to `children` and it is automatically guarded. You never forget to add the guard because there is only one place to add routes.

**`replace` prevents history loops:**
Without `replace`: login → redirect to home fails → redirect to /login (new history entry) → user presses back → goes to home → redirect to /login again → infinite loop.
With `replace`: the /home entry is replaced by /login — there is no protected URL in the history to go back to.

**`persist` + `ProtectedRoute` on page reload:**
On reload: Zustand rehydrates from `localStorage` synchronously before first render. `ProtectedRoute` reads `token` — it's already populated. The user stays on their protected page. Without `persist`, every reload would redirect to login.

**Interview questions:**

- _How does a route guard work in React?_ — A component that checks a condition (token present) during render. If the condition fails, it returns a redirect. If it passes, it renders `<Outlet />` which renders the matched child route.
- _Why use a layout route for ProtectedRoute instead of wrapping each page individually?_ — DRY. One guard, all protected pages. Adding a new page doesn't require remembering to add the guard. Forgetting to wrap a page would be a security bug.
- _What is the difference between authentication and authorization?_ — Authentication: "who are you?" (login, token). Authorization: "are you allowed to do this?" (role checks, e.g. Admin-only actions). `ProtectedRoute` handles authentication. Role checks (e.g. hiding the leave button for ADMINs) handle authorization.

---

## 8. Error Handling — Client vs Server Errors

**Two categories of errors in the auth flow:**

**Client-side errors (Zod):**
Caught before any network request. Empty email, invalid format, password too short. React Hook Form runs Zod on submit and prevents the request if validation fails. Errors show immediately, zero network cost.

**Server-side errors (HTTP status codes):**
Caught in `onError` of `useMutation`. These are things Zod can't know — wrong password, email already taken, server down.

```ts
// LoginForm — map HTTP status to human message
onError: (error) => {
  const is401 = axios.isAxiosError(error) && error.response?.status === 401
  setError('root', {
    message: is401 ? 'Invalid email or password' : 'Something went wrong.'
  })
}

// RegisterForm — map 409 to field-level error
onError: (error) => {
  const data = axios.isAxiosError(error) && error.response?.data
  if (data?.message?.includes('email')) {
    setError('email', { message: 'This email is already registered' })
  } else if (data?.message?.includes('username')) {
    setError('username', { message: 'This username is taken' })
  }
}
```

**`errors.root` for banner-level errors:**
Field errors (`errors.email`) appear under a specific input. Root errors (`errors.root`) appear in a banner at the top of the form. Use root for errors that are not tied to one field — wrong password, network failure.

**Why not a global toast for auth errors:**
Auth errors are contextual — they belong to the form that caused them. A global toast ("Invalid password") disappears and leaves the user confused about which action failed. Field or banner errors stay visible until the user fixes them.

**Interview questions:**

- _What is the difference between a field error and a root error in React Hook Form?_ — A field error is tied to a specific input (email is invalid). A root error is a form-level error not tied to any field (wrong password, server error). Access root error with `errors.root`.
- _How do you distinguish different server errors in the `onError` callback?_ — Check `error.response?.status` for the HTTP status code (401, 409, 500), and optionally `error.response?.data` for the structured error body with field-specific messages.
- _What is the security reason for a generic "Invalid email or password" message on login?_ — Specific messages like "email not found" or "wrong password" allow attackers to enumerate valid emails. A generic message reveals nothing — the attacker cannot distinguish between a non-existent account and a wrong password.
