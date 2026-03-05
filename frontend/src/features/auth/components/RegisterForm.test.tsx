import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import { useAuthStore } from '@/store/authStore'
import RegisterForm from './RegisterForm'

vi.mock('@/lib/stompClient', () => ({
  connectStomp: vi.fn(),
  disconnectStomp: vi.fn(),
  stompClient: { connected: false },
}))

// Valid registration payload — used as a base, overridden per test
const VALID = {
  displayName: 'Alice',
  username: 'alice_92',
  email: 'alice@example.com',
  password: 'Secret1@',
}

async function fillForm(
  user: ReturnType<typeof import('@testing-library/user-event').default.setup>,
  overrides: Partial<typeof VALID> = {},
) {
  const values = { ...VALID, ...overrides }
  await user.type(screen.getByLabelText('Display name'), values.displayName)
  await user.type(screen.getByLabelText('Username'), values.username)
  await user.type(screen.getByLabelText('Email'), values.email)
  await user.type(screen.getByLabelText('Password'), values.password)
}

describe('RegisterForm', () => {
  beforeEach(() => {
    useAuthStore.getState().clearAuth()
  })

  // ─── Validation ───────────────────────────────────────────────────

  it('shows password error when password has no special character', async () => {
    const { user } = renderWithProviders(<RegisterForm />)

    await fillForm(user, { password: 'Password1' }) // missing special char
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText(/must contain at least one special character/i),
    ).toBeInTheDocument()
  })

  it('shows username error when username contains invalid characters', async () => {
    const { user } = renderWithProviders(<RegisterForm />)

    await fillForm(user, { username: 'alice 92' }) // space is not allowed
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(
      await screen.findByText(/letters, digits and underscores only/i),
    ).toBeInTheDocument()
  })

  // ─── 409 conflict — field mapping ─────────────────────────────────

  it('maps 409 "Email already taken" to the email field', async () => {
    const { user } = renderWithProviders(<RegisterForm />)

    await fillForm(user, { email: 'taken@example.com' })
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('This email is already taken')).toBeInTheDocument()
    // Error appears under the email field, not as a root banner
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('maps 409 "Username already taken" to the username field', async () => {
    const { user } = renderWithProviders(<RegisterForm />)

    await fillForm(user, { username: 'taken_user' })
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('This username is already taken')).toBeInTheDocument()
  })

  // ─── Happy path ───────────────────────────────────────────────────

  it('stores token in auth store on successful registration', async () => {
    const { user } = renderWithProviders(<RegisterForm />)

    await fillForm(user)
    await user.click(screen.getByRole('button', { name: /create account/i }))

    await screen.findByRole('button', { name: /create account/i })
    expect(useAuthStore.getState().token).toBe('fake-jwt-token')
  })
})
