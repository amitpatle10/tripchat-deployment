import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { InfiniteData } from '@tanstack/react-query'
import type { MessageResponse } from '@/types'
import { chatApi } from '../api'

/**
 * useDeleteMessage — mutation hook for soft-deleting a chat message.
 *
 * Pattern: Optimistic Update
 *   The message is immediately marked deleted=true in the TanStack Query cache
 *   before the API call completes. If the server returns an error, we roll back
 *   to the previous cache state. This gives instant UI feedback without waiting
 *   for the network round-trip.
 *
 * STOMP broadcast:
 *   The backend also broadcasts the deletion to all group members via STOMP.
 *   The existing addMessage handler in useMessages handles this by replacing
 *   the matching entry (by clientId) with deleted=true — idempotent with our
 *   optimistic update.
 */
export function useDeleteMessage(groupId: string) {
  const queryClient = useQueryClient()

  const { mutate: deleteMessage } = useMutation({
    mutationFn: (messageId: string) => chatApi.deleteMessage(groupId, messageId),

    // Optimistic update — mark the message as deleted before the server confirms
    onMutate: async (messageId: string) => {
      const key = ['messages', groupId]

      // Snapshot current cache for rollback on error
      const previous = queryClient.getQueryData<InfiniteData<MessageResponse[]>>(key)

      queryClient.setQueryData<InfiniteData<MessageResponse[]>>(key, (old) => {
        if (!old) return old
        return {
          ...old,
          pages: old.pages.map((page) =>
            page.map((m) => (m.id === messageId ? { ...m, deleted: true } : m)),
          ),
        }
      })

      return { previous }
    },

    // Rollback on API error (e.g. 403 not your message, network failure)
    onError: (_err, _messageId, context) => {
      if (context?.previous) {
        queryClient.setQueryData(['messages', groupId], context.previous)
      }
    },
  })

  return { deleteMessage }
}
