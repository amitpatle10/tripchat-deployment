import { create } from 'zustand'

// Holds cross-cutting real-time state that multiple components need.
// wsConnected bridges the STOMP lifecycle into React — stompClient updates
// this flag via onConnect/onDisconnect, and hooks subscribe to it as a
// dependency so they resubscribe automatically after a reconnect.
interface ChatState {
  wsConnected: boolean
  setWsConnected: (connected: boolean) => void
}

export const useChatStore = create<ChatState>((set) => ({
  wsConnected: false,
  setWsConnected: (wsConnected) => set({ wsConnected }),
}))
