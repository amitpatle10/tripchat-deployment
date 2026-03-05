import { http, HttpResponse } from 'msw'
import type { MessageResponse } from '@/types'

export const mockMessage = (overrides: Partial<MessageResponse> = {}): MessageResponse => ({
  id: 'msg-1',
  groupId: 'group-1',
  senderId: 'user-1',
  senderUsername: 'alice_92',
  senderDisplayName: 'Alice',
  content: 'Hello everyone!',
  clientId: 'client-uuid-1',
  createdAt: '2025-07-01T10:00:00.000Z',
  deleted: false,
  ...overrides,
})

export const chatHandlers = [
  http.get('/api/v1/groups/:groupId/messages', () => {
    return HttpResponse.json([mockMessage()])
  }),

  http.post('/api/v1/groups/:groupId/read', () => {
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/groups/:groupId/presence', () => {
    return HttpResponse.json([])
  }),
]
