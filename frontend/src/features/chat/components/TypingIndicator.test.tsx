import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import type { TypingPayload } from '@/types'
import TypingIndicator from './TypingIndicator'

const makeUser = (userId: string, displayName: string): TypingPayload => ({
  userId,
  username: displayName.toLowerCase(),
  displayName,
  typing: true,
})

describe('TypingIndicator', () => {
  // ─── Empty state ──────────────────────────────────────────────────

  it('renders nothing when no users are typing', () => {
    const { container } = renderWithProviders(<TypingIndicator typingUsers={{}} />)
    expect(container.firstChild).toBeNull()
  })

  // ─── Single user ─────────────────────────────────────────────────

  it('shows "{name} is typing..." for one user', () => {
    renderWithProviders(
      <TypingIndicator typingUsers={{ 'user-1': makeUser('user-1', 'Alice') }} />,
    )
    expect(screen.getByText('Alice is typing...')).toBeInTheDocument()
  })

  // ─── Two users ────────────────────────────────────────────────────

  it('shows "{name} and {name} are typing..." for two users', () => {
    renderWithProviders(
      <TypingIndicator
        typingUsers={{
          'user-1': makeUser('user-1', 'Alice'),
          'user-2': makeUser('user-2', 'Bob'),
        }}
      />,
    )
    expect(screen.getByText('Alice and Bob are typing...')).toBeInTheDocument()
  })

  // ─── Three or more users ──────────────────────────────────────────

  it('shows "Several people are typing..." for 3+ users', () => {
    renderWithProviders(
      <TypingIndicator
        typingUsers={{
          'user-1': makeUser('user-1', 'Alice'),
          'user-2': makeUser('user-2', 'Bob'),
          'user-3': makeUser('user-3', 'Carol'),
        }}
      />,
    )
    expect(screen.getByText('Several people are typing...')).toBeInTheDocument()
  })

  // ─── Animated dots ────────────────────────────────────────────────

  it('renders three animated dots when users are typing', () => {
    const { container } = renderWithProviders(
      <TypingIndicator typingUsers={{ 'user-1': makeUser('user-1', 'Alice') }} />,
    )
    // Three span dots with animate-bounce class
    const dots = container.querySelectorAll('.animate-bounce')
    expect(dots).toHaveLength(3)
  })
})
