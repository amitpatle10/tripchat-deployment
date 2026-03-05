import { useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { InfiniteData } from '@tanstack/react-query'
import { useAuthStore } from '@/store/authStore'
import { stompClient } from '@/lib/stompClient'
import type { MessageResponse } from '@/types'

export function useSendMessage(groupId: string) {
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)

  const send = useCallback(
    (content: string) => {
      if (!user || !stompClient.connected) return

      const clientId = crypto.randomUUID()

      // Optimistic message — shown immediately, opacity-60 until confirmed.
      // id is fake; server assigns the real id and echoes it back via clientId.
      const optimistic: MessageResponse = {
        id: `optimistic-${clientId}`,
        groupId,
        senderId: user.id,
        senderUsername: user.username,
        senderDisplayName: user.displayName,
        content,
        clientId,
        createdAt: new Date().toISOString(),
        deleted: false,
      }

      // Prepend to the newest page in the cache so it appears at the bottom.
      queryClient.setQueryData<InfiniteData<MessageResponse[]>>(
        ['messages', groupId],
        (old) => {
          if (!old) return old
          return {
            ...old,
            pages: [
              [optimistic, ...old.pages[0]],
              ...old.pages.slice(1),
            ],
          }
        },
      )

      // Publish to STOMP broker. Server broadcasts to /topic/groups/{groupId}/messages
      // and sends confirmation to /user/queue/confirmation — both handled by addMessage.
      stompClient.publish({
        destination: `/app/groups/${groupId}/messages`,
        body: JSON.stringify({ clientId, content }),
      })

      // Fallback: if the STOMP session drops between send and confirmation, the
      // optimistic entry never gets replaced. After 3s, check if it's still in
      // the cache with a fake id — if so, the confirmation was lost, so refetch
      // to pull the real message from the server.
      setTimeout(() => {
        const cached = queryClient.getQueryData<InfiniteData<MessageResponse[]>>(['messages', groupId])
        const stillOptimistic = cached?.pages.some((page) =>
          page.some((m) => m.clientId === clientId && m.id.startsWith('optimistic-')),
        )
        if (stillOptimistic) {
          queryClient.invalidateQueries({ queryKey: ['messages', groupId] })
        }
      }, 3000)
    },
    [groupId, queryClient, user],
  )

  return { send }
}
