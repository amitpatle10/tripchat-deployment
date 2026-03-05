// Mirrors API_CONTRACTS.md exactly. Update here first when backend shapes change —
// TypeScript will surface every broken callsite.

export interface UserResponse {
  id: string
  email: string
  username: string
  displayName: string
}

export interface AuthResponse {
  token: string
  tokenType: string
  expiresIn: number
  user: UserResponse
}

export interface GroupResponse {
  id: string
  name: string
  description: string | null
  inviteCode: string
  memberCount: number
  myRole: 'ADMIN' | 'MEMBER'
  createdBy: string
  createdAt: string
  unreadCount: number
}

export interface MessageResponse {
  id: string
  groupId: string
  senderId: string | null        // null when sender's account was deleted
  senderUsername: string | null  // null when sender's account was deleted
  senderDisplayName: string | null
  content: string
  clientId: string
  createdAt: string
  deleted: boolean
}

export interface GroupMemberResponse {
  userId: string
  username: string
  displayName: string
  role: 'ADMIN' | 'MEMBER'
  joinedAt: string
}

export interface PresenceUser {
  userId: string
  username: string
  displayName: string
}

// STOMP payloads
export interface TypingPayload {
  userId: string
  username: string
  displayName: string
  typing: boolean
}

// Error shape returned by backend on 4xx/5xx
export interface ApiError {
  timestamp: string
  status: number
  error: string
  message: string
  details?: Record<string, string> // present on 400 validation errors only
}
