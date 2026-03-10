import { useEffect, useMemo } from 'react'
import { useParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '@/store/authStore'
import type { GroupResponse, MessageResponse } from '@/types'
import { chatApi } from '../api'
import { useGroups } from '@/features/groups/hooks/useGroups'
import { useMessages } from '../hooks/useMessages'
import { useSendMessage } from '../hooks/useSendMessage'
import { useDeleteMessage } from '../hooks/useDeleteMessage'
import { useTyping } from '../hooks/useTyping'
import { usePresence } from '../hooks/usePresence'
import { useStompSubscription } from '../hooks/useStompSubscription'
import ChatHeader from './ChatHeader'
import MessageList from './MessageList'
import TypingIndicator from './TypingIndicator'
import MessageInput from './MessageInput'

// Container — owns all state and subscriptions, passes data down as props.
// No visual markup except the layout shell.
export default function ChatPage() {
  const { groupId } = useParams<{ groupId: string }>()
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)

  // useGroups keeps the ['groups'] cache warm for both the normal navigation path
  // (GroupListPage already called it) and the direct-URL / reload path (cache is
  // empty — this triggers a fetch so group name and member count appear without
  // requiring a reload). TanStack Query deduplicates the request when the cache is
  // already fresh, so there is no extra network call in the happy path.
  const { data: groups = [] } = useGroups()
  const group = groups.find((g) => g.id === groupId)

  // Server state — paginated message history + cache mutation helper
  const {
    data,
    isLoading,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    addMessage,
  } = useMessages(groupId!)

  const { send } = useSendMessage(groupId!)
  const { deleteMessage } = useDeleteMessage(groupId!)
  const { typingUsers, sendTyping } = useTyping(groupId!)
  const { data: onlineUsers = [] } = usePresence(groupId!)

  // Flatten pages for MessageList.
  // TanStack Query stores pages newest-first (pages[0] = most recent 50).
  // Reverse pages so older pages come first, reverse each page so oldest message
  // is at index 0 — rendering top-to-bottom gives the correct chat chronology.
  const messages = useMemo(
    () =>
      (data?.pages ?? [])
        .slice()
        .reverse()
        .flatMap((page) => page.slice().reverse()),
    [data],
  )

  // STOMP — receive messages broadcast to all group members.
  // Backend broadcasts to /topic/groups/{groupId} (no /messages suffix).
  // addMessage handles both new messages and optimistic confirmation (by clientId match).
  useStompSubscription(`/topic/groups/${groupId}`, (frame) => {
    addMessage(JSON.parse(frame.body) as MessageResponse)
  })

  // STOMP — personal confirmation of own sends.
  // Filter by groupId so stale frames from a previous chat don't bleed in.
  // addMessage deduplicates against the broadcast so this is always safe to call.
  useStompSubscription('/user/queue/confirmation', (frame) => {
    const msg = JSON.parse(frame.body) as MessageResponse
    if (msg.groupId === groupId) addMessage(msg)
  })

  // Mark group as read on mount — resets server unread count.
  // Also zero out the unread badge in the groups list cache immediately (optimistic).
  useEffect(() => {
    if (!groupId) return
    chatApi.markAsRead(groupId).catch(() => {})
    queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) =>
      old.map((g) => (g.id === groupId ? { ...g, unreadCount: 0 } : g)),
    )
  }, [groupId, queryClient])

  return (
    <div className="h-screen h-dvh flex flex-col bg-gray-950 overflow-hidden">

      <ChatHeader group={group} onlineUsers={onlineUsers} />

      {/* flex-1 + overflow-hidden: MessageList owns its own scroll container */}
      <div className="flex-1 overflow-hidden">
        <MessageList
          messages={messages}
          currentUserId={user?.id ?? ''}
          isLoading={isLoading}
          hasNextPage={hasNextPage}
          isFetchingNextPage={isFetchingNextPage}
          onLoadMore={fetchNextPage}
          onDelete={deleteMessage}
        />
      </div>

      <div className="shrink-0">
        <TypingIndicator typingUsers={typingUsers} />
        <MessageInput onSend={send} sendTyping={sendTyping} />
      </div>

    </div>
  )
}
