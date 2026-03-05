import { http, HttpResponse } from 'msw'
import type { AuthResponse } from '@/types'

export const mockUser = {
  id: '1',
  email: 'alice@example.com',
  username: 'alice_92',
  displayName: 'Alice',
}

export const mockAuthResponse: AuthResponse = {
  token: 'fake-jwt-token',
  tokenType: 'Bearer',
  expiresIn: 86400000,
  user: mockUser,
}

export const authHandlers = [
  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string }

    if (body.email === 'alice@example.com' && body.password === 'Secret1@') {
      return HttpResponse.json(mockAuthResponse)
    }
    return HttpResponse.json(
      { timestamp: new Date().toISOString(), status: 401, error: 'Unauthorized', message: 'Bad credentials' },
      { status: 401 },
    )
  }),

  http.post('/api/v1/auth/register', async ({ request }) => {
    const body = (await request.json()) as Record<string, string>

    if (body.email === 'taken@example.com') {
      return HttpResponse.json(
        { timestamp: new Date().toISOString(), status: 409, error: 'Conflict', message: 'Email already taken' },
        { status: 409 },
      )
    }
    if (body.username === 'taken_user') {
      return HttpResponse.json(
        { timestamp: new Date().toISOString(), status: 409, error: 'Conflict', message: 'Username already taken' },
        { status: 409 },
      )
    }
    return HttpResponse.json(mockAuthResponse, { status: 201 })
  }),
]
