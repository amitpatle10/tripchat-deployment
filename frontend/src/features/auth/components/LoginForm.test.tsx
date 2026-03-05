import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/utils'
import { server } from '@/test/mswServer'
import { useAuthStore } from '@/store/authStore'
import LoginForm from './LoginForm'

// Prevent STOMP from opening a real WebSocket during tests
vi.mock('@/lib/stompClient', () => ({
  connectStomp: vi.fn(),
  disconnectStomp: vi.fn(),
  stompClient: { connected: false },
}))

describe('LoginForm', () => {
  beforeEach(() => {
    // Reset auth store between tests so state doesn't bleed
    useAuthStore.getState().clearAuth()
  })

  // ─── Validation ───────────────────────────────────────────────────

  it('shows email error when form is submitted with no email', async () => {
    const { user } = renderWithProviders(<LoginForm />)

    await user.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument()
  })

  it('shows password error when email is filled but password is empty', async () => {
    const { user } = renderWithProviders(<LoginForm />)

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByText('Password is required')).toBeInTheDocument()
  })

  // ─── Server errors ────────────────────────────────────────────────

  it('shows error banner when credentials are wrong (401)', async () => {
    const { user } = renderWithProviders(<LoginForm />)

    await user.type(screen.getByLabelText('Email'), 'wrong@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrongpassword')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument()
  })

  it('shows generic error banner on network failure', async () => {
    server.use(
      http.post('/api/v1/auth/login', () => HttpResponse.error()),
    )
    const { user } = renderWithProviders(<LoginForm />)

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret1@')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByText(/something went wrong/i)).toBeInTheDocument()
  })

  // ─── Loading state ────────────────────────────────────────────────

  it('disables button and shows loading text while request is in flight', async () => {
    // delay('infinite') keeps the request pending so we can assert the loading state
    server.use(
      http.post('/api/v1/auth/login', async () => {
        await new Promise(() => {}) // never resolves — simulates in-flight request
        return HttpResponse.json({})
      }),
    )
    const { user } = renderWithProviders(<LoginForm />)

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret1@')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    expect(await screen.findByRole('button', { name: /signing in/i })).toBeDisabled()
  })

  // ─── Happy path ───────────────────────────────────────────────────

  it('stores token in auth store on successful login', async () => {
    const { user } = renderWithProviders(<LoginForm />)

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'Secret1@')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    // Wait for mutation to complete and Zustand to be updated
    await screen.findByRole('button', { name: /sign in/i }) // button text reverts
    expect(useAuthStore.getState().token).toBe('fake-jwt-token')
    expect(useAuthStore.getState().user?.email).toBe('alice@example.com')
  })
})
