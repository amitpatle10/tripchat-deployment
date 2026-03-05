import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { UserResponse } from '@/types'

interface AuthState {
  user: UserResponse | null
  token: string | null
  setAuth: (user: UserResponse, token: string) => void
  clearAuth: () => void
}

// persist middleware saves token + user to localStorage automatically.
// On page reload, Zustand rehydrates from localStorage — user stays logged in.
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      setAuth: (user, token) => set({ user, token }),
      clearAuth: () => set({ user: null, token: null }),
    }),
    { name: 'tripchat-auth' }, // localStorage key
  ),
)
