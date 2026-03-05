import { useRef, useLayoutEffect, useCallback } from 'react'
import type { MessageResponse } from '@/types'
import MessageBubble from './MessageBubble'

interface MessageListProps {
  messages: MessageResponse[]
  currentUserId: string
  isLoading: boolean
  hasNextPage: boolean | undefined
  isFetchingNextPage: boolean
  onLoadMore: () => void
  onDelete: (messageId: string) => void
}

// Owns all scroll logic — no parent needs to know about scroll position.
export default function MessageList({
  messages,
  currentUserId,
  isLoading,
  hasNextPage,
  isFetchingNextPage,
  onLoadMore,
  onDelete,
}: MessageListProps) {
  const scrollRef = useRef<HTMLDivElement>(null)

  // Tracks whether the user is near the bottom.
  // True → auto-scroll when new messages arrive. False → user is reading history, don't jump.
  const isNearBottomRef = useRef(true)

  // Saved before fetchNextPage so we can restore position after older messages
  // are prepended (prevents the visual jump when content height grows at the top).
  const prevScrollHeightRef = useRef(0)

  const handleLoadMore = useCallback(() => {
    if (!scrollRef.current || !hasNextPage || isFetchingNextPage) return
    // Snapshot scroll height BEFORE the new page renders.
    prevScrollHeightRef.current = scrollRef.current.scrollHeight
    onLoadMore()
  }, [hasNextPage, isFetchingNextPage, onLoadMore])

  // useLayoutEffect runs synchronously after DOM mutations and before the browser
  // paints — critical here because we need to adjust scrollTop before the user
  // sees the content shift.
  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!el) return

    if (prevScrollHeightRef.current > 0) {
      // Older page was prepended — restore relative position so visible content doesn't jump.
      el.scrollTop += el.scrollHeight - prevScrollHeightRef.current
      prevScrollHeightRef.current = 0
    } else if (isNearBottomRef.current) {
      // New message arrived and user was at the bottom — follow it down.
      el.scrollTop = el.scrollHeight
    }
  }, [messages])

  const handleScroll = () => {
    const el = scrollRef.current
    if (!el) return

    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
    isNearBottomRef.current = distFromBottom < 100

    // Trigger load-more when the user scrolls within 80px of the top.
    if (el.scrollTop < 80 && hasNextPage && !isFetchingNextPage) {
      handleLoadMore()
    }
  }

  if (isLoading) {
    return (
      <div className="h-full flex items-center justify-center">
        <p className="text-gray-500 text-sm">Loading messages...</p>
      </div>
    )
  }

  return (
    <div
      ref={scrollRef}
      onScroll={handleScroll}
      className="h-full overflow-y-auto overflow-x-hidden flex flex-col py-4"
    >
      {/* Older-page loading indicator at the very top */}
      {isFetchingNextPage && (
        <div className="flex justify-center py-3">
          <p className="text-gray-500 text-xs">Loading older messages...</p>
        </div>
      )}

      {/* Empty state */}
      {messages.length === 0 && !isFetchingNextPage && (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-gray-500 text-sm">No messages yet. Say hello!</p>
        </div>
      )}

      {/* mt-auto pushes messages to the bottom when they don't fill the viewport */}
      <div className="flex flex-col gap-0.5 mt-auto">
        {messages.map((message) => (
          <MessageBubble
            key={message.id}
            message={message}
            isOwn={message.senderId === currentUserId}
            onDelete={onDelete}
          />
        ))}
      </div>
    </div>
  )
}
