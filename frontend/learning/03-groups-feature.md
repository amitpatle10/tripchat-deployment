# TripChat Frontend — Groups Feature

> Everything learned while building the group list, modals, and group interactions.
> Covers Container/Presenter, TanStack Query mutations, optimistic UI, and form patterns.

---

## Table of Contents

1. [Container / Presenter Pattern](#1-container--presenter-pattern)
2. [TanStack Query Mutations — Three Strategies](#2-tanstack-query-mutations--three-strategies)
3. [Optimistic Updates — How the Mechanics Work](#3-optimistic-updates--how-the-mechanics-work)
4. [Per-Card Loading State](#4-per-card-loading-state)
5. [React Hook Form + Zod for Group Forms](#5-react-hook-form--zod-for-group-forms)
6. [Modal Pattern — No Library Needed](#6-modal-pattern--no-library-needed)
7. [Key Patterns Summary](#7-key-patterns-summary)

---

## 1. Container / Presenter Pattern

**The simple version:**
Split every feature into two kinds of components — one that *knows things* and one that *shows things*.

- **Container** — fetches data, owns state, handles events. Has zero visual HTML of its own. Think of it as the "brain."
- **Presenter** — receives props, renders UI. Has zero data-fetching, zero mutations. Think of it as the "face."

**In TripChat — the GroupListPage tree:**

```
GroupListPage (Container)
├── knows: groups list (useGroups), modal flags, leavingGroupId
├── knows: how to leave a group (useLeaveGroup)
└── renders ↓

  GroupList (Presenter)
  └── receives: groups[], leavingGroupId, onLeave callback
      └── renders ↓

        GroupCard (Presenter)
        └── receives: one GroupResponse, isLeaving flag, onLeave callback
            └── renders: name, admin badge, unread count, leave button
```

**Why GroupCard has zero hooks (except `useNavigate`):**
`GroupCard` is a pure presenter. You can test it by rendering `<GroupCard group={mockGroup} onLeave={() => {}} isLeaving={false} />` — no query providers, no mocking. If `GroupCard` also called `useLeaveGroup`, it would need the full QueryClient provider in tests. Separation makes testing easy.

**What Single Responsibility means in practice:**
- `GroupListPage` has one reason to change: data fetching or orchestration logic changes.
- `GroupCard` has one reason to change: the card's visual design changes.
- They never change for each other's reason.

**The alternative — fat components:**
One `GroupListPage` that fetches data, renders all cards, handles all mutations, and manages all modal state. Works at first. Becomes a 400-line file with 10 concerns in 6 months. Container/Presenter is the preemptive cut.

**Interview questions:**

- _What is the Container/Presenter pattern?_ — Separate "how do I get data" from "how do I display data" into two component types. Containers are smart. Presenters are dumb (intentionally).
- _Why make a component a Presenter?_ — Testability (no mocks needed), reusability, and Single Responsibility. A Presenter with only props is the easiest component to test.
- _When does this split become unnecessary?_ — For very small, one-off components that will never be reused or tested independently. If a component will never grow past 50 lines, the split adds overhead without benefit.

---

## 2. TanStack Query Mutations — Three Strategies

**Every mutation in the groups feature uses a different strategy. This is intentional.**

### Strategy A — Optimistic Update (useCreateGroup)

Update the UI before the server responds. Roll back if the server rejects.

```
User submits "Create group" form
        ↓
onMutate  →  snapshot current list, insert fake group into cache
        ↓      (UI shows new card instantly)
POST /api/v1/groups  (request in flight)
        ↓
onSuccess →  replace fake group with real server data
onError   →  restore the snapshot (rollback, card disappears)
```

**Why optimistic for create?**
`POST /groups` returns the full `GroupResponse`. We have enough to build a realistic placeholder card. The wait for 200ms feels broken when we could show the result immediately.

### Strategy B — Invalidation (useJoinGroup)

Wait for the server to respond, then tell TanStack Query to refetch the list.

```
User submits invite code
        ↓
POST /api/v1/groups/join  (request in flight — UI waits)
        ↓
onSuccess →  queryClient.invalidateQueries({ queryKey: ['groups'] })
           →  TanStack Query automatically refetches GET /groups
           →  List updates with the newly joined group
```

**Why invalidation for join?**
You send an invite code. You don't know which group it resolves to, what it's named, how many members it has, or any other display info. You cannot build a meaningful optimistic card — it would show blank fields. Better to wait 200ms for real data than show a loading ghost card.

### Strategy C — Optimistic + Always Refetch (useLeaveGroup)

Remove the card immediately for instant feel, but always refetch after the mutation settles.

```
User clicks leave on a MEMBER card
        ↓
onMutate   →  snapshot list, remove the card from cache immediately
        ↓       (card disappears instantly)
DELETE /api/v1/groups/{id}/leave
        ↓
onError    →  restore snapshot (card reappears if server rejected)
onSettled  →  invalidateQueries(['groups']) — always, success or error
```

**Why both optimistic remove AND invalidation?**
The optimistic remove is for UX — the card disappears instantly. The `onSettled` invalidation is a safety net — it syncs the cache with server truth after the mutation regardless of outcome. If the leave succeeded, the refetch confirms it. If it failed (e.g. 400 "admin cannot leave"), the rollback already restored the card, and the refetch ensures the cache is clean.

**`onSettled` vs `onSuccess`:**
`onSuccess` only fires when the mutation succeeds. `onSettled` fires in both cases — like a `finally` block. For the safety-net invalidation, we always want it to run.

**Interview questions:**

- _What is an optimistic update?_ — Updating the UI immediately as if the server agreed, then rolling back if it didn't. Used when you're confident the operation will succeed and want instant feedback.
- _When should you NOT use optimistic updates?_ — When you don't have enough info to build a useful placeholder (join by invite code). When the operation can fail for common reasons (e.g. payment processing). When the rollback would be jarring or confusing.
- _What is the difference between `onSuccess`, `onError`, and `onSettled`?_ — `onSuccess`: fires when mutation succeeds. `onError`: fires when mutation fails. `onSettled`: fires in both cases, always. Like try/catch/finally — `onSettled` is finally.

---

## 3. Optimistic Updates — How the Mechanics Work

**Step 1 — Cancel in-flight queries:**

```ts
await queryClient.cancelQueries({ queryKey: ['groups'] })
```

If a background refetch is already running when you apply the optimistic update, it could complete and overwrite your new data with the old list. Cancelling prevents the race condition.

**Step 2 — Snapshot the current state:**

```ts
const previousGroups = queryClient.getQueryData<GroupResponse[]>(['groups'])
```

This is the rollback target. If the server rejects the mutation, you restore this snapshot in `onError`.

**Step 3 — Write the optimistic entry:**

```ts
const tempId = `optimistic-${Date.now()}`  // stable within this mutation

queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) => [
  { id: tempId, name: variables.name, ... },  // fake entry at top
  ...old,
])

return { previousGroups, tempId }  // stored in mutation context
```

`tempId` is stored in the mutation's `context` object. It's how `onSuccess` finds and replaces exactly the right optimistic entry.

**Step 4 — Replace on success, restore on error:**

```ts
onSuccess: (newGroup, _vars, context) => {
  queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) =>
    old.map(g => g.id === context.tempId ? newGroup : g)  // precise replacement
  )
},
onError: (_err, _vars, context) => {
  queryClient.setQueryData(['groups'], context.previousGroups)  // rollback
}
```

**Why `context.tempId` and not `id.startsWith('optimistic-')`:**
If the user creates two groups quickly, both have optimistic entries. `startsWith('optimistic-')` would match both, replacing the first with the second group's data. `context.tempId` is unique to this specific mutation — it matches only the entry it created.

**Interview questions:**

- _Walk me through the full optimistic update lifecycle._ — (1) Cancel in-flight queries. (2) Snapshot current state. (3) Insert fake entry, store snapshot and tempId in context. (4) Send request. On success: replace fake with real. On error: restore snapshot from context.
- _What is a race condition in the context of optimistic updates?_ — A background refetch completing after the optimistic update was applied, overwriting the fake entry with old data. `cancelQueries` before applying the optimistic update prevents this.
- _What happens to the fake entry if the network drops entirely?_ — `onError` fires, `context.previousGroups` is restored, and the fake entry disappears. The user sees the card vanish — they can retry.

---

## 4. Per-Card Loading State

**The problem:**
`useMutation` exposes one `isPending` boolean. But there are N group cards on screen. If you bind `disabled={isPending}` to every leave button, all leave buttons go disabled when any one mutation is in flight.

**The solution — track the specific ID being mutated:**

```ts
// In GroupListPage (Container)
const [leavingGroupId, setLeavingGroupId] = useState<string | null>(null)

const handleLeave = (groupId: string) => {
  setLeavingGroupId(groupId)              // mark this specific card
  leaveGroup(groupId, {
    onSettled: () => setLeavingGroupId(null)  // clear when done (always)
  })
}

// Passed to each GroupCard
isLeaving={leavingGroupId === group.id}   // true only for the card being left
```

**Why `onSettled` and not `onSuccess` to clear the state:**
If `leaveGroup` fails (e.g. 400 "admin cannot leave"), `onSuccess` never fires. The leave button would stay disabled forever. `onSettled` always runs, so the button always re-enables.

**Why this state lives in `GroupListPage` (local `useState`), not Zustand:**
Only `GroupListPage` cares about which group is mid-leave. No other component outside this page needs to know. Colocate state at the smallest scope that needs it. Zustand is for cross-feature state that multiple distant parts of the app share.

**The general pattern — "which item is loading":**

```ts
// Works for any list with per-item async operations:
const [processingId, setProcessingId] = useState<string | null>(null)

const handleAction = (id: string) => {
  setProcessingId(id)
  doSomething(id, { onSettled: () => setProcessingId(null) })
}

// Per-item
isProcessing={processingId === item.id}
```

**Interview questions:**

- _How do you show per-item loading state in a list?_ — Track the ID of the item being acted on in local state. Each item compares its own ID to decide if it's loading.
- _What is the principle of colocation for state?_ — Keep state as close as possible to where it's used. Only lift up when multiple components share it. Only go global when multiple features share it.
- _Why does one `isPending` from `useMutation` not work for list items?_ — `isPending` is scoped to the mutation instance. All items share the same mutation — only one item fires at a time, but `isPending` gives no info about which one. You need the ID to know which item is in flight.

---

## 5. React Hook Form + Zod for Group Forms

**Group form schemas:**

```ts
const createGroupSchema = z.object({
  name:        z.string().min(3, 'At least 3 characters').max(50, 'Max 50 characters'),
  description: z.string().max(500, 'Max 500 characters').optional(),
})

const joinGroupSchema = z.object({
  inviteCode: z.string().length(8, 'Invite code must be exactly 8 characters'),
})
```

`description` is `optional()` — the field can be absent or undefined. If the textarea is left empty, the value is `""` (empty string), which passes validation. The backend treats empty string as "no description".

**Resetting the form on modal open:**

```ts
useEffect(() => {
  if (open) reset()
}, [open, reset])
```

Without this: close the modal after a failed submit, re-open it — the previous error messages are still visible. `reset()` clears all field values and all error state. Called when `open` flips to `true`.

**`setError` for server-side errors (JoinGroupModal):**

```ts
onError: (error) => {
  if (error.response?.status === 404) {
    setError('inviteCode', { message: 'Invite code not found' })
  } else if (error.response?.status === 409) {
    setError('inviteCode', { message: "You're already a member of this group" })
  }
}
```

Zod can only validate format (length = 8). Whether the code actually exists or whether the user is already a member — only the server knows. `setError` injects field-level errors after the fact without re-running Zod.

**The difference from auth error handling:**
In LoginForm, we use `setError('root', ...)` for a banner error (wrong password is not a field problem). In JoinGroupModal, we use `setError('inviteCode', ...)` because the error is directly about that specific field — the invite code is wrong or invalid. The error appears right under the input, not in a separate banner.

**Interview questions:**

- _How do you reset a React Hook Form when a modal opens?_ — Call `reset()` inside a `useEffect` that runs when the `open` prop becomes `true`. This clears values and errors so the user sees a clean form each time.
- _When do you use `setError('root', ...)` vs `setError('fieldName', ...)`?_ — Root for errors not tied to any field (network failure, wrong credentials). Field-specific for errors about that exact input (invite code not found, email already taken).
- _What is the advantage of `optional()` in Zod vs making the field required with `min(0)`?_ — `optional()` allows the field to be absent from the object entirely. `min(0)` still requires the field to be present, just allows empty strings. For optional form fields, `optional()` more accurately reflects the intent.

---

## 6. Modal Pattern — No Library Needed

**How the modals work:**

```tsx
// GroupListPage — controls when modals are visible
const [showCreate, setShowCreate] = useState(false)

<CreateGroupModal open={showCreate} onClose={() => setShowCreate(false)} />
```

```tsx
// CreateGroupModal — self-contained, owned form state and mutation
if (!open) return null  // unmounts when closed — form state resets automatically

return (
  <div className="fixed inset-0 z-50 flex items-center justify-center">
    <div className="absolute inset-0 bg-black/60" onClick={onClose} />  {/* backdrop */}
    <div className="relative ...">  {/* panel */}
      ...form...
    </div>
  </div>
)
```

**`if (!open) return null` — why unmount instead of hide:**
`display: none` hides the modal visually but keeps it mounted. The form still holds its previous values and errors. Returning `null` fully unmounts — RHF's internal state is gone. The `useEffect` that calls `reset()` on open is a backup; unmounting is the primary reset mechanism.

**`e.stopPropagation()` on the panel:**
The backdrop `onClick` closes the modal. Without `stopPropagation()` on the panel, clicking anywhere inside the panel would bubble up to the backdrop and close the modal mid-interaction.

**`z-50` on the container:**
Fixed positioning takes the modal out of the normal document flow. `z-50` ensures it renders above all other content (header `z-10`, page content default `z-0`).

**`role="dialog"` and `aria-modal="true"`:**
These ARIA attributes tell screen readers this is a modal dialog. `aria-labelledby` links the modal to its title heading — screen readers announce "New group, dialog" when the modal opens.

**Why no modal library (e.g. Radix, HeadlessUI):**
Libraries add focus trapping, scroll locking, and keyboard management (`Escape` to close). For Phase 1, a simple div-based modal is sufficient. The tradeoff is documented — add a library when accessibility requirements tighten.

**Interview questions:**

- _What is the difference between hiding a modal with CSS vs unmounting it?_ — CSS hide keeps the component mounted — state, effects, and subscriptions persist. Unmounting removes everything. For forms: unmount resets all input values automatically. For modals: unmount is usually preferred.
- _What is event bubbling and why does it matter for modals?_ — Events propagate up through the DOM tree. A click on the modal panel bubbles up to the backdrop, which would close the modal. `e.stopPropagation()` on the panel breaks the bubble chain.
- _What ARIA attributes are needed for an accessible modal?_ — `role="dialog"`, `aria-modal="true"`, `aria-labelledby` pointing to the title. Ideally also focus trapping (first focusable element gets focus on open, Tab cycles within the modal, Escape closes it).

---

## 7. Key Patterns Summary

| Pattern | File | What it solves |
|---|---|---|
| **Container/Presenter** | `GroupListPage` + `GroupCard` | Separates fetching from rendering — Presenters testable with just props |
| **Optimistic Update** | `useCreateGroup`, `useLeaveGroup` | Instant UI feedback — update cache first, roll back if server rejects |
| **Cache Invalidation** | `useJoinGroup` | When you don't know the result ahead of time — let the server respond first |
| **onSettled safety net** | `useLeaveGroup` | Always sync cache with server truth after any mutation, success or failure |
| **tempId in context** | `useCreateGroup` | Precise optimistic entry replacement when multiple entries could exist |
| **cancelQueries** | `useCreateGroup`, `useLeaveGroup` | Prevent background refetches from overwriting optimistic state |
| **Per-item loading ID** | `leavingGroupId` in `GroupListPage` | One `isPending` can't track which of N items is in flight — track the ID |
| **setError for server errors** | `JoinGroupModal` | Map HTTP status codes to field-level errors after Zod has already passed |
| **if (!open) return null** | `CreateGroupModal`, `JoinGroupModal` | Unmounting resets form state — cleaner than CSS hide |
