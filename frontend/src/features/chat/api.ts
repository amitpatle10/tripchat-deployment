import api from '@/lib/axios'
import type { MessageResponse, PresenceUser } from '@/types'

export type MessageCursor = { cursorTime: string; cursorId: string } | undefined

export const chatApi = {
  // Cursor params omitted on first page (newest 50 messages).
  // Provide both cursorTime + cursorId to page backward (older messages).
  getMessages: (groupId: string, cursor?: MessageCursor) =>
    api
      .get<MessageResponse[]>(`/groups/${groupId}/messages`, { params: cursor })
      .then((r) => r.data),

  // Call when user opens a group chat — resets unread count to 0 on the server.
  markAsRead: (groupId: string) =>
    api.post(`/groups/${groupId}/read`),

  // Snapshot of who is online right now. Called once on mount.
  getPresence: (groupId: string) =>
    api.get<PresenceUser[]>(`/groups/${groupId}/presence`).then((r) => r.data),

  // Soft-delete a message. Only the sender can delete their own message.
  // Returns 204 No Content on success. Backend also broadcasts deleted=true via STOMP.
  deleteMessage: (groupId: string, messageId: string) =>
    api.delete(`/groups/${groupId}/messages/${messageId}`),
}
