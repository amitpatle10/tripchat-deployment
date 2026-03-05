import { useState, useCallback } from 'react'
import { useAuthStore } from '@/store/authStore'
import { stompClient } from '@/lib/stompClient'
import type { TypingPayload } from '@/types'
import { useStompSubscription } from './useStompSubscription'

export function useTyping(groupId: string) {
  // userId → payload map. Using an object (not array) means updates and deletes
  // are O(1) key operations instead of O(n) filter/find over an array.
  const [typingUsers, setTypingUsers] = useState<Record<string, TypingPayload>>({})
  const currentUserId = useAuthStore((s) => s.user?.id)

  // Subscribe to typing events from other members.
  // Suppress own events — API contract says to filter by userId === currentUser.
  useStompSubscription(`/topic/groups/${groupId}/typing`, (frame) => {
    const payload = JSON.parse(frame.body) as TypingPayload
    if (payload.userId === currentUserId) return

    setTypingUsers((prev) => {
      if (payload.typing) {
        return { ...prev, [payload.userId]: payload }
      }
      // typing: false → remove the entry
      const next = { ...prev }
      delete next[payload.userId]
      return next
    })
  })

  // Called by MessageInput: true on first keystroke, false on send/clear.
  // MessageInput also sends a refresh every 3s to keep the server's 5s TTL alive.
  const sendTyping = useCallback(
    (typing: boolean) => {
      if (!stompClient.connected) return
      stompClient.publish({
        destination: `/app/groups/${groupId}/typing`,
        body: JSON.stringify({ typing }),
      })
    },
    [groupId],
  )

  return { typingUsers, sendTyping }
}
