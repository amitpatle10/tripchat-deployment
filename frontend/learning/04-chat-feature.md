# TripChat Frontend — Chat Feature Design

> End-to-end walkthrough of how the chat page works, in plain English.
> Every flow has a diagram. Every decision has a reason.

---

## Table of Contents

1. [The Chat Page Layout](#1-the-chat-page-layout)
2. [Loading Message History — How Pagination Works](#2-loading-message-history--how-pagination-works)
3. [How Pages Are Flattened for Display](#3-how-pages-are-flattened-for-display)
4. [Sending a Message — The Full Journey](#4-sending-a-message--the-full-journey)
5. [Receiving Messages in Real Time](#5-receiving-messages-in-real-time)
6. [Deduplication — Why the Same Message Arrives Twice](#6-deduplication--why-the-same-message-arrives-twice)
7. [The Scroll System](#7-the-scroll-system)
8. [Typing Indicators — End to End](#8-typing-indicators--end-to-end)
9. [Online Presence](#9-online-presence)
10. [Mark as Read](#10-mark-as-read)
11. [The Complete Component Map](#11-the-complete-component-map)
12. [Interview Questions](#12-interview-questions)

---

## 1. The Chat Page Layout

```
┌─────────────────────────────────────────┐
│              ChatHeader                 │  ← back button, group name, online avatars
├─────────────────────────────────────────┤
│                                         │
│                                         │
│            MessageList                  │  ← flex-1, this section scrolls
│         (scroll container)              │
│                                         │
│   [older messages loaded above]         │
│                                         │
│        Hi everyone!           10:01 AM  │  ← other user, left-aligned, gray bubble
│                                         │
│   10:02 AM          Hey, what's up? ●  │  ← own message, right-aligned, indigo
│                                         │
│        Great trip idea!       10:03 AM  │
│                                         │
│   10:04 AM             Totally agree ●  │
│                                         │
├─────────────────────────────────────────┤
│  Alice is typing...                     │  ← TypingIndicator (hidden when nobody types)
├─────────────────────────────────────────┤
│  [  Message...          (Shift+Enter) ] │  ← MessageInput textarea
│                                    [→]  │  ← send button
└─────────────────────────────────────────┘
```

**CSS layout trick — `h-screen flex flex-col`:**
The page is exactly the screen height. The header and input are fixed-size (`shrink-0`). MessageList gets `flex-1` — whatever space is left after header and input. `overflow-hidden` on the middle div makes MessageList the scroll container, not the whole page. This prevents the browser address bar and keyboard from shifting the layout on mobile.

---

## 2. Loading Message History — How Pagination Works

The backend returns messages **newest-first**, 50 at a time. To load older messages, you send a cursor pointing to the oldest message you already have.

```
First page (no cursor):
GET /api/v1/groups/{id}/messages
→ [msg_100, msg_99, msg_98, ... msg_51]   ← 50 newest messages, newest first

User scrolls to top → second page:
GET /api/v1/groups/{id}/messages?cursorTime=...&cursorId=msg_51_id
→ [msg_50, msg_49, msg_48, ... msg_1]     ← next 50 older messages

User scrolls to top again → third page:
→ []    ← empty array = no more history, stop loading
```

**TanStack Query stores pages in an array:**

```
data.pages = [
  [msg_100, msg_99, ... msg_51],   ← pages[0] — newest (loaded first)
  [msg_50,  msg_49, ... msg_1],    ← pages[1] — older (loaded second)
]
```

**The cursor is always the LAST item in the last page:**
Because each page is newest-first, the last item in a page is the oldest message in that page. That becomes the cursor for the next load.

```
pages[0] = [msg_100, msg_99, ... msg_51]
                                   ↑
                         oldest in page → cursor for next page
                         { cursorTime: msg_51.createdAt, cursorId: msg_51.id }
```

**When does pagination stop?**
When the server returns fewer than 50 messages. `getNextPageParam` returns `undefined` → `hasNextPage` becomes `false` → no more load-more triggers.

---

## 3. How Pages Are Flattened for Display

TanStack Query gives us `data.pages` — a nested array. MessageList needs a flat array. AND it needs to show oldest messages at the top, newest at the bottom (chat convention).

**The problem:**
```
data.pages = [
  [msg_100, msg_99, ..., msg_51],   ← newer page, newest-first within
  [msg_50,  msg_49, ..., msg_1],    ← older page, newest-first within
]

What we want for rendering (oldest at top):
[msg_1, msg_2, ... msg_50, msg_51, ... msg_100]
```

**The two-step reverse:**

```
Step 1 — reverse the pages array (older pages come first):
[
  [msg_50, msg_49, ..., msg_1],    ← older page now first
  [msg_100, msg_99, ..., msg_51],  ← newer page now second
]

Step 2 — reverse each page (oldest message first within each page):
[
  [msg_1, msg_2, ..., msg_50],     ← oldest to newest
  [msg_51, msg_52, ..., msg_100],  ← oldest to newest
]

Step 3 — flatMap (join into one flat array):
[msg_1, msg_2, ..., msg_50, msg_51, ..., msg_100]  ✓
```

**In code:**
```ts
const messages = data.pages
  .slice()                         // don't mutate original
  .reverse()                       // step 1
  .flatMap(page => page.slice().reverse())  // step 2 + 3
```

---

## 4. Sending a Message — The Full Journey

This is the most important flow to understand. There are four actors: the UI, the cache, the STOMP broker, and the server.

```
USER TYPES AND PRESSES ENTER
         │
         ▼
MessageInput.handleSend()
  content = "Hey!"
  calls onSend("Hey!")  (prop from ChatPage)
         │
         ▼
useSendMessage.send("Hey!")
  ① Generate clientId = crypto.randomUUID()  →  "abc-123"
         │
         ▼
  ② Build optimistic message:
     {
       id: "optimistic-abc-123",    ← fake id
       clientId: "abc-123",
       content: "Hey!",
       senderId: currentUser.id,
       createdAt: now,
       deleted: false
     }
         │
         ▼
  ③ Write to TanStack Query cache  →  message appears in UI immediately
     (opacity-60 — "pending" look)
         │
         ▼
  ④ stompClient.publish(
       destination: "/app/groups/{id}/messages",
       body: { clientId: "abc-123", content: "Hey!" }
     )
         │
         │   [network round-trip to server]
         │
         ▼
  ⑤ SERVER receives, persists, assigns real id: "real-uuid-456"
         │
    ┌────┴────────────────────────────────┐
    │                                     │
    ▼                                     ▼
BROADCAST                         CONFIRMATION
/topic/groups/{id}/messages       /user/queue/confirmation
{                                 {
  id: "real-uuid-456",              id: "real-uuid-456",
  clientId: "abc-123",  ←same→     clientId: "abc-123",
  content: "Hey!",                  content: "Hey!",
  ...                               ...
}                                 }
    │                                     │
    ▼                                     ▼
useStompSubscription              useStompSubscription
calls addMessage(msg)             calls addMessage(msg)
         │                                │
         ▼                                ▼
    addMessage sees clientId "abc-123" already in cache
    → replaces optimistic entry with real message
    → opacity goes from 60 → 100  (solidifies)
    → id changes from "optimistic-abc-123" → "real-uuid-456"
```

**Why optimistic UI here?**
Without it: you type "Hey!", press Enter, see nothing for 200ms, then the message appears. With it: the message appears the instant you press Enter, solidifies when confirmed. Feels instant.

**What if the STOMP publish fails?**
The optimistic message stays in the cache at opacity-60 indefinitely (we don't have a rollback for STOMP failures in Phase 1). In production, you'd add a timeout: if no confirmation within N seconds, mark the message as failed with a retry button.

---

## 5. Receiving Messages in Real Time

When someone else sends a message, it arrives via STOMP subscription — no polling, no HTTP request.

```
OTHER USER sends "Paris is amazing!"
         │
         ▼
Server broadcasts to /topic/groups/{id}/messages
         │
         ▼
YOUR browser (subscribed via useStompSubscription)
receives the frame
         │
         ▼
ChatPage's subscription handler:
  useStompSubscription(`/topic/groups/${groupId}/messages`, (frame) => {
    addMessage(JSON.parse(frame.body))
  })
         │
         ▼
addMessage checks: does "their-client-id" exist in the cache?
  → NO (it's someone else's message, not an optimistic entry)
  → Prepend to pages[0] (newest page)
         │
         ▼
React re-renders MessageList
  → New message appears at bottom
  → If user was near bottom, MessageList auto-scrolls down
```

**Two subscriptions in ChatPage:**

```
/topic/groups/{groupId}/messages   ← ALL messages (from everyone including yourself)
/user/queue/confirmation           ← YOUR sends only (confirmation receipt)

Both call the same addMessage() function.
addMessage() handles everything based on whether clientId exists in cache.
```

---

## 6. Deduplication — Why the Same Message Arrives Twice

When YOU send a message, you receive it **twice**:
1. Via `/topic/groups/{id}/messages` (the broadcast to everyone)
2. Via `/user/queue/confirmation` (your personal receipt)

Both carry the same `clientId`. Without deduplication, your message would appear twice.

**The `addMessage` decision tree:**

```
addMessage(incoming) called
         │
         ▼
Does any message in the cache have the same clientId?
         │
    ┌────┴────────────────┐
   YES                    NO
    │                      │
    ▼                      ▼
Replace that entry      Prepend to pages[0]
(optimistic → real,     (new message from
 or idempotent          another user)
 re-delivery)
```

**Example timeline for your own message:**

```
T=0ms   You press Enter
        → optimistic "abc-123" added to cache

T=5ms   Broadcast arrives (/topic/groups/...)
        → addMessage({ clientId: "abc-123", id: "real-456", ... })
        → "abc-123" found in cache → REPLACE optimistic with real
        → Message solidifies

T=7ms   Confirmation arrives (/user/queue/confirmation)
        → addMessage({ clientId: "abc-123", id: "real-456", ... })
        → "abc-123" still in cache (now as real message)
        → REPLACE again — same data, idempotent, no visual change
```

No duplicates. No special "already confirmed" flag needed. Just replace.

---

## 7. The Scroll System

The scroll system in `MessageList` has three distinct behaviors. Each is handled by a different mechanism.

### Behavior 1 — Scroll to bottom on first load

```
Messages finish loading
         │
         ▼
useLayoutEffect fires (after DOM update, before browser paint)
  isNearBottomRef.current = true  (initialized to true — user starts at bottom)
  prevScrollHeightRef.current = 0  (no pending load-more)
         │
         ▼
→ el.scrollTop = el.scrollHeight  (jump to bottom)
```

### Behavior 2 — Follow new messages (if near bottom)

```
New message arrives → messages array updates
         │
         ▼
useLayoutEffect fires
  prevScrollHeightRef.current = 0  (no load-more in progress)
  isNearBottomRef.current = ?
         │
    ┌────┴──────────────────┐
  true (within 100px)      false (scrolled up)
    │                         │
    ▼                         ▼
scroll to bottom          do nothing
                          (user is reading history)
```

### Behavior 3 — Scroll position restoration after loading older messages

This is the trickiest. When older messages load at the top, the content height increases. Without compensation, the visible messages jump down.

```
User scrolls to top → handleLoadMore() called
         │
         ▼
BEFORE fetchNextPage():
  prevScrollHeightRef.current = scrollRef.scrollHeight
  (e.g. 2000px)
         │
         ▼
fetchNextPage() runs → older messages prepend to pages array
         │
         ▼
useLayoutEffect fires
  prevScrollHeightRef.current > 0  → restore mode
         │
         ▼
  new scrollHeight = 3500px  (1500px of new content added)
  scrollTop += 3500 - 2000 = +1500px
         │
         ▼
The visible content stays in place. No jump.
prevScrollHeightRef.current = 0  (reset flag)
```

**Why `useLayoutEffect` and not `useEffect`?**

```
useEffect timing:
  DOM updates → browser PAINTS → effect runs
                    ↑
                 user sees the jump here (flash)

useLayoutEffect timing:
  DOM updates → effect runs → browser PAINTS
                    ↑
                 scrollTop adjusted here, before paint
                 user never sees the jump
```

### The `isNearBottom` check — how it works

```
┌─────────────────────────────┐  ─┐
│                             │   │
│                             │   │ scrollHeight = 2000px
│     (content above)        │   │
│                             │   │
│─────────────────────────────│  ─┼─ scrollTop = 1700px
│                             │   │
│  [visible area]             │   │ clientHeight = 200px
│                             │   │
└─────────────────────────────┘  ─┘

distFromBottom = scrollHeight - scrollTop - clientHeight
               = 2000 - 1700 - 200
               = 100px

isNearBottom = distFromBottom < 100  →  true (right at the edge)
```

If `distFromBottom < 100px`, the user is considered "at the bottom" → auto-scroll on new messages.

---

## 8. Typing Indicators — End to End

### Sending side (MessageInput)

```
User starts typing "H"
         │
         ▼
handleChange → value is non-empty → startTyping()
         │
         ▼
startTyping():
  isTypingRef = true
  sendTyping(true)  →  STOMP publish /app/groups/{id}/typing { typing: true }
  start interval: every 3s → sendTyping(true) again
  (keeps server's 5s TTL alive)
         │
User presses Enter (sends message)
         │
         ▼
handleSend → stopTyping()
         │
         ▼
stopTyping():
  isTypingRef = false
  sendTyping(false)  →  STOMP publish { typing: false }
  clearInterval
```

**The server's dead man's switch:**

```
Client sends typing: true at T=0
Server sets TTL: user X is typing, expires at T=5s

Client sends typing: true again at T=3s   ← interval refresh
Server resets TTL: expires at T=8s

Client stops refreshing (tab closed, crash, etc.)
TTL expires at T=8s
Server auto-removes the typing entry
Other clients see the indicator disappear
```

No explicit "typing stopped" message needed for crash cases — the TTL handles it.

### Receiving side (useTyping)

```
STOMP frame arrives on /topic/groups/{id}/typing:
{
  userId: "bob-id",
  displayName: "Bob",
  typing: true
}
         │
         ▼
Is userId === currentUser.id?
  YES → ignore (don't show "You are typing")
  NO  → continue
         │
         ▼
typing: true?
  → setTypingUsers(prev => ({ ...prev, "bob-id": payload }))

typing: false?
  → setTypingUsers(prev => { delete prev["bob-id"]; return copy })
```

### Display (TypingIndicator)

```
typingUsers = {
  "bob-id": { displayName: "Bob" },
  "alice-id": { displayName: "Alice" }
}

names = ["Bob", "Alice"]
length = 2  → "Bob and Alice are typing..."

─────────────────────────────────────
typingUsers = {}
names = []
→ return null  (component renders nothing, takes no space)
```

---

## 9. Online Presence

```
ChatPage mounts
         │
         ▼
usePresence(groupId) → useQuery
         │
         ▼
GET /api/v1/groups/{id}/presence
→ [{ userId, username, displayName }, ...]
         │
         ▼
ChatHeader receives onlineUsers[]
         │
   ┌─────┴──────────────────┐
   │                        │
onlineUsers.length > 0    onlineUsers.length = 0
   │                        │
   ▼                        ▼
Show avatars             Show member count
"3 online"               "12 members"
(first letter of          (from group.memberCount)
 displayName in circle)
```

**Why `staleTime: Infinity`?**
Presence is a snapshot — it shows who was online when you opened the chat. Refetching every minute wouldn't add value and would waste requests. The heartbeat system (`stompClient.ts`) keeps each user's TTL alive. If you want live presence, you'd subscribe to a presence STOMP topic — not poll REST. For Phase 1, a snapshot on open is enough.

---

## 10. Mark as Read

When you open a group chat, there's a badge on the group card showing unread messages. That badge should disappear the moment you open the chat.

```
ChatPage mounts
         │
         ▼
useEffect (runs once on mount):
         │
    ┌────┴──────────────────────────────────────┐
    │                                           │
    ▼                                           ▼
POST /api/v1/groups/{id}/read            Update groups cache
(server resets unread count to 0)        optimistically:
(best-effort — .catch(() => {}))
                                   groups list: find this group
                                   → set unreadCount: 0
                                   → badge on GroupCard disappears
                                      instantly (no refetch needed)
```

**Why update the cache optimistically AND call the server?**
- Cache update: makes the badge disappear the instant ChatPage opens (before the HTTP response)
- Server call: persists the read state so the server tracks it correctly for future sessions

The server call is "best-effort" — if it fails (network blip), the badge will reappear next time the groups list is fetched. That's acceptable — the user's read state isn't critical to store perfectly in Phase 1.

---

## 11. The Complete Component Map

```
ChatPage (Container)
│
│  STATE OWNED:
│  ├── messages        ← useMessages (useInfiniteQuery)
│  ├── typingUsers     ← useTyping (useState)
│  ├── onlineUsers     ← usePresence (useQuery)
│  ├── group           ← read from ['groups'] cache
│
│  STOMP SUBSCRIPTIONS:
│  ├── /topic/groups/{id}/messages  → addMessage()
│  └── /user/queue/confirmation     → addMessage() (filtered by groupId)
│
│  SIDE EFFECTS:
│  └── markAsRead on mount + update groups cache
│
├── ChatHeader (Presenter)
│   receives: group, onlineUsers
│   renders:  back button, group name, online count, avatar circles
│
├── MessageList (Presenter + Scroll owner)
│   receives: messages[], currentUserId, isLoading, hasNextPage, isFetchingNextPage, onLoadMore
│   owns:     scrollRef, isNearBottomRef, prevScrollHeightRef
│   renders:  loading skeleton OR message list
│   │
│   └── MessageBubble (Pure Presenter)  × N
│       receives: message, isOwn
│       renders:  indigo bubble (own) or gray bubble (other)
│                 opacity-60 when optimistic, 100 when confirmed
│                 "This message was deleted" tombstone if deleted
│                 "Deleted User" if sender was deleted
│
├── TypingIndicator (Pure Presenter)
│   receives: typingUsers Record
│   renders:  animated dots + "X is typing..." text, or null
│
└── MessageInput (Stateful Presenter)
    receives: onSend, sendTyping
    owns:     content (useState), isTypingRef, typingIntervalRef
    renders:  auto-grow textarea + send button
```

**Data flow summary:**

```
                    ┌─────────────────────────────┐
                    │          ChatPage            │
                    │  (the single source of truth)│
                    └──┬──────┬──────┬──────┬─────┘
                       │      │      │      │
              props    │      │      │      │   props
              ┌────────┘  ┌───┘  ┌───┘  └────────┐
              ▼           ▼      ▼                ▼
         ChatHeader  MessageList  TypingIndicator  MessageInput
                          │
                          │ props
                          ▼
                    MessageBubble × N


Callbacks flow UP:
  MessageInput.onSend       → ChatPage → useSendMessage
  MessageInput.sendTyping   → ChatPage → useTyping → STOMP
  MessageList.onLoadMore    → ChatPage → fetchNextPage
```

---

## 12. Interview Questions

**On useInfiniteQuery:**
- _What is cursor-based pagination and why is it better than offset-based?_ — Offset pagination (`page=2&size=50`) breaks when items are added between requests — items shift and you see duplicates or skip items. Cursor-based pagination (`after=message_51`) always continues from where you left off regardless of new items being added.
- _What is `getNextPageParam` for?_ — It receives the last fetched page and derives the parameters needed to fetch the next one. If it returns `undefined`, `hasNextPage` becomes `false` and pagination stops.
- _How do you add new real-time data to an `useInfiniteQuery` cache?_ — `queryClient.setQueryData` with a function that returns the updated `InfiniteData<T>` shape. Prepend to `pages[0]` (newest page) for new messages.

**On optimistic UI:**
- _What is the difference between optimistic UI for REST mutations vs STOMP publishes?_ — REST mutations (useMutation) have `onSuccess`/`onError` hooks. STOMP has no built-in ack — confirmation comes via a separate subscription. The rollback mechanism is different: REST rolls back on HTTP error; STOMP relies on confirmation arrival (or a timeout in production).
- _Why use `clientId` instead of a temporary fake id for deduplication?_ — `clientId` is generated by the client and included in the server's response. It's the shared key between the optimistic entry and the real confirmation. A fake `id` is just local and meaningless — the server doesn't echo it back.

**On scroll:**
- _Why does chat use `useLayoutEffect` instead of `useEffect` for scroll position?_ — `useEffect` fires after the browser paints, so the user sees the content jump for one frame before the scroll correction. `useLayoutEffect` fires after the DOM update but before the paint — the correction happens invisibly.
- _What is the `prevScrollHeight` pattern?_ — Save `scrollHeight` before appending content at the top. After the DOM updates, the new `scrollHeight` minus the saved value is exactly how much content was added. Adding that difference to `scrollTop` keeps the visible content stationary.

**On typing indicators:**
- _What is a dead man's switch in software?_ — A process that must actively signal "I'm alive" on a timer. If it stops (crash, close), it's presumed dead and cleaned up automatically. The typing refresh every 3s is a dead man's switch — if the client stops refreshing, the server's 5s TTL expires and the typing indicator clears.
- _Why use a ref instead of state for `isTyping`?_ — Because `startTyping` and `stopTyping` are called inside event handlers and `setInterval`. These are closures — they capture the value at the time they're created. State inside a closure goes stale; a ref always returns `ref.current`, which is always the latest value.

**On the component architecture:**
- _Why does `MessageList` own the scroll logic instead of `ChatPage`?_ — Single Responsibility. Scroll position is a display concern — it's about how messages are presented, not what messages exist. Keeping it in `MessageList` means `ChatPage` doesn't need to know about `scrollRef`, `isNearBottomRef`, or `prevScrollHeightRef`.
- _Why is `addMessage` returned from `useMessages` instead of being defined in `ChatPage`?_ — Because `addMessage` directly mutates the `useInfiniteQuery` cache — it needs `queryClient` and `groupId` which are already in scope inside the hook. Defining it in `ChatPage` would mean importing `useQueryClient` and repeating the cache key. Encapsulation: the hook owns the data, the hook owns the mutations to that data.
