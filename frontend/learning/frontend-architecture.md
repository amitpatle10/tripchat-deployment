# TripChat Frontend — Learning Guide Index

> Interview-ready breakdowns of every pattern, decision, and concept used in this project.
> Split by category — start with base architecture, then follow the features in build order.

---

## Files

| File                                                   | Covers                                                                                                                            |
| ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| [`01-base-architecture.md`](./01-base-architecture.md) | Vite, TypeScript, state layers, Zustand, TanStack Query, Axios, React Router, WebSocket/STOMP, project structure                  |
| [`02-auth-feature.md`](./02-auth-feature.md)           | Login/register flow end-to-end, Zod schemas, React Hook Form, useMutation, authStore with persist, ProtectedRoute, error handling |
| [`03-groups-feature.md`](./03-groups-feature.md)       | Container/Presenter pattern, optimistic updates, cache invalidation, per-card loading state, group form patterns, modal pattern   |
| [`04-chat-feature.md`](./04-chat-feature.md)           | Chat layout, cursor pagination, page flattening, optimistic send, real-time delivery, deduplication, scroll system, typing, presence |

---

## Quick Concept Lookup

| Concept                                        | File | Section                 |
| ---------------------------------------------- | ---- | ----------------------- |
| Why Vite over Webpack                          | `01` | §1 Build Tool           |
| Three-layer state model                        | `01` | §3 State Management     |
| Zustand selectors + persist                    | `01` | §4 Zustand              |
| staleTime vs gcTime                            | `01` | §5 TanStack Query       |
| Axios interceptors                             | `01` | §6 Axios                |
| ProtectedRoute + Outlet                        | `01` | §7 React Router         |
| STOMP + wsConnected bridge                     | `01` | §8 WebSocket            |
| Feature-based folder rule                      | `01` | §9 Project Structure    |
| Login flow order (why setAuth before navigate) | `02` | §1 Auth Flow            |
| Zod schema for auth                            | `02` | §2 Zod Schemas          |
| Controlled vs uncontrolled inputs              | `02` | §3 React Hook Form      |
| useMutation + onSuccess/onError split          | `02` | §4 useMutation          |
| authStore + persist mechanics                  | `02` | §5 authStore            |
| Why WebSocket connects on login                | `02` | §6 WebSocket on Login   |
| Presence heartbeat / dead man's switch         | `02` | §6 WebSocket on Login   |
| Generic "invalid credentials" security reason  | `02` | §8 Error Handling       |
| Container/Presenter split                      | `03` | §1 Container/Presenter  |
| Optimistic vs invalidation vs both             | `03` | §2 Three Strategies     |
| cancelQueries + tempId + rollback mechanics    | `03` | §3 Optimistic Mechanics |
| Per-item loading with ID tracking              | `03` | §4 Per-Card Loading     |
| setError for server errors in forms            | `03` | §5 Group Forms          |
| Modal unmount vs CSS hide                      | `03` | §6 Modal Pattern        |
| Chat page CSS layout (h-screen flex)           | `04` | §1 Layout               |
| Cursor-based pagination vs offset              | `04` | §2 Pagination           |
| Two-step page reverse for display              | `04` | §3 Page Flattening      |
| Optimistic send flow (4 steps + diagram)       | `04` | §4 Sending a Message    |
| Real-time message delivery via STOMP           | `04` | §5 Receiving Messages   |
| clientId deduplication (broadcast + confirm)   | `04` | §6 Deduplication        |
| useLayoutEffect scroll restoration             | `04` | §7 Scroll System        |
| Typing dead man's switch + ref pattern         | `04` | §8 Typing Indicators    |
| Presence snapshot + staleTime: Infinity        | `04` | §9 Online Presence      |
| Mark as read — optimistic + server             | `04` | §10 Mark as Read        |
