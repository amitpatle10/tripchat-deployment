import { http, HttpResponse } from 'msw'
import type { GroupResponse } from '@/types'

export const mockGroup: GroupResponse = {
  id: 'group-1',
  name: 'Paris Trip 2025',
  description: 'Planning our summer trip',
  inviteCode: 'AB12CD34',
  memberCount: 5,
  myRole: 'MEMBER',
  createdBy: 'user-1',
  createdAt: '2025-07-01T10:00:00.000Z',
  unreadCount: 3,
}

export const mockAdminGroup: GroupResponse = {
  ...mockGroup,
  id: 'group-2',
  name: 'Rome Trip',
  description: null,
  myRole: 'ADMIN',
  unreadCount: 0,
}

export const groupHandlers = [
  http.get('/api/v1/groups', () => {
    return HttpResponse.json([mockGroup, mockAdminGroup])
  }),

  http.post('/api/v1/groups', async ({ request }) => {
    const body = (await request.json()) as { name: string; description?: string }
    return HttpResponse.json(
      {
        ...mockGroup,
        id: 'new-group-1',
        name: body.name,
        description: body.description ?? null,
        myRole: 'ADMIN',
        unreadCount: 0,
      },
      { status: 201 },
    )
  }),

  http.post('/api/v1/groups/join', async ({ request }) => {
    const body = (await request.json()) as { inviteCode: string }

    if (body.inviteCode === 'NOTFOUND') {
      return HttpResponse.json(
        { status: 404, error: 'Not Found', message: 'Invite code not found', timestamp: '' },
        { status: 404 },
      )
    }
    if (body.inviteCode === 'MEMBER01') {
      return HttpResponse.json(
        { status: 409, error: 'Conflict', message: 'Already a member', timestamp: '' },
        { status: 409 },
      )
    }
    return HttpResponse.json(mockGroup)
  }),

  http.delete('/api/v1/groups/:id/leave', () => {
    return new HttpResponse(null, { status: 204 })
  }),
]
