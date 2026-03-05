import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useChatStore } from '@/store/chatStore'

let heartbeatInterval: ReturnType<typeof setInterval> | null = null

export const stompClient = new Client({
  // SockJS handles the transport — falls back to HTTP long-polling if WebSocket
  // is blocked by a corporate proxy or firewall.
  webSocketFactory: () => new SockJS('/ws'),

  // Wait 5 seconds before attempting to reconnect after a dropped connection.
  // @stomp/stompjs handles reconnect automatically — we just configure the delay.
  reconnectDelay: 5000,

  onConnect: () => {
    useChatStore.getState().setWsConnected(true)

    // Presence heartbeat — server TTL is 30s, we send every 20s.
    // Two missed heartbeats (40s) = user appears offline.
    heartbeatInterval = setInterval(() => {
      if (stompClient.connected) {
        stompClient.publish({ destination: '/app/presence/heartbeat' })
      }
    }, 20_000)
  },

  // onWebSocketClose fires on any socket closure — expected or unexpected.
  // This is the right place to signal disconnection, because onDisconnect
  // only fires on an explicit deactivate() call (i.e. logout), not on drops.
  onWebSocketClose: () => {
    useChatStore.getState().setWsConnected(false)

    if (heartbeatInterval) {
      clearInterval(heartbeatInterval)
      heartbeatInterval = null
    }
  },

  // onDisconnect fires only when deactivate() is called (logout).
  // wsConnected is already false from onWebSocketClose, so nothing extra needed.
  onDisconnect: () => {},

  onStompError: (frame) => {
    console.error('[STOMP] broker error:', frame.headers['message'], frame.body)
  },
})

// Called once after a successful login. Injects the JWT into the STOMP
// CONNECT frame — this is how Spring authenticates the WebSocket session.
export function connectStomp(token: string) {
  stompClient.configure({
    connectHeaders: { Authorization: `Bearer ${token}` },
  })
  stompClient.activate()
}

// Called on logout. Clears the heartbeat and closes the connection cleanly.
export function disconnectStomp() {
  if (heartbeatInterval) {
    clearInterval(heartbeatInterval)
    heartbeatInterval = null
  }
  void stompClient.deactivate()
}
