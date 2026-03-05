import { useCallback } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import type { InfiniteData } from '@tanstack/react-query'
import type { MessageResponse } from '@/types'
import { chatApi, type MessageCursor } from '../api'

export function useMessages(groupId: string) {
  const queryClient = useQueryClient()

  const query = useInfiniteQuery({
    queryKey: ['messages', groupId],
    queryFn: ({ pageParam }: { pageParam: MessageCursor }) =>
      chatApi.getMessages(groupId, pageParam),

    // No cursor on first page — server returns the newest 50 messages.
    initialPageParam: undefined as MessageCursor,

    // The cursor for the next (older) page is the oldest message in the current page.
    // API returns newest-first, so the oldest message is the last item in the array.
    // Return undefined when < 50 results arrive — no more history to load.
    getNextPageParam: (lastPage): MessageCursor => {
      if (lastPage.length < 50) return undefined
      const oldest = lastPage[lastPage.length - 1]
      return { cursorTime: oldest.createdAt, cursorId: oldest.id }
    },
  })

  // Single handler for both broadcast and confirmation deliveries.
  // Logic:
  //   - clientId already in cache (own optimistic message) → replace with real server data
  //   - clientId not in cache (message from another user) → prepend to newest page
  // This handles deduplication: broadcast + confirmation carry the same clientId.
  // Whichever arrives first replaces the optimistic entry; the second is a no-op replace.
  const addMessage = useCallback(
    (incoming: MessageResponse) => {
      queryClient.setQueryData<InfiniteData<MessageResponse[]>>(
        ['messages', groupId],
        (old) => {
          if (!old) return old

          const existsInCache = old.pages.some((page) =>
            page.some((m) => m.clientId === incoming.clientId),
          )

          if (existsInCache) {
            // Replace the matching entry (optimistic → confirmed, or idempotent re-delivery)
            return {
              ...old,
              pages: old.pages.map((page) =>
                page.map((m) => (m.clientId === incoming.clientId ? incoming : m)),
              ),
            }
          }

          // New message from another user — prepend to the newest page (index 0)
          return {
            ...old,
            pages: [
              [incoming, ...old.pages[0]],
              ...old.pages.slice(1),
            ],
          }
        },
      )
    },
    [groupId, queryClient],
  )

  return { ...query, addMessage }
}
