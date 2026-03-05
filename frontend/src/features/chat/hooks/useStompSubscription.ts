import { useEffect, useRef } from 'react'
import type { IMessage } from '@stomp/stompjs'
import { stompClient } from '@/lib/stompClient'
import { useChatStore } from '@/store/chatStore'

// Subscribes to a STOMP destination for the lifetime of the calling component.
// Unsubscribes automatically on unmount or when the destination changes.
// Resubscribes automatically after a reconnect (wsConnected is the trigger).
export function useStompSubscription(
  destination: string,
  onMessage: (frame: IMessage) => void,
) {
  const wsConnected = useChatStore((s) => s.wsConnected)

  // Store the callback in a ref so that if the caller passes an inline function,
  // we don't resubscribe on every render. The subscription is tied to the
  // destination, not the callback — but the latest callback is always called.
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage

  useEffect(() => {
    // Don't attempt to subscribe if the client isn't connected yet.
    // When wsConnected flips to true (onConnect fires), this effect re-runs.
    if (!wsConnected) return

    const subscription = stompClient.subscribe(destination, (frame) => {
      onMessageRef.current(frame)
    })

    // Cleanup: unsubscribe when the component unmounts or destination changes.
    return () => subscription.unsubscribe()
  }, [wsConnected, destination])
}
