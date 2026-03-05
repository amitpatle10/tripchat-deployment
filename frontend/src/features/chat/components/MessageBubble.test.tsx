import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import { mockMessage } from '@/test/handlers/chat'
import MessageBubble from './MessageBubble'

describe('MessageBubble', () => {
  // ─── Content rendering ────────────────────────────────────────────

  it('renders the message content', () => {
    renderWithProviders(<MessageBubble message={mockMessage()} isOwn={false} />)
    expect(screen.getByText('Hello everyone!')).toBeInTheDocument()
  })

  it('shows sender name for messages from others', () => {
    renderWithProviders(<MessageBubble message={mockMessage()} isOwn={false} />)
    expect(screen.getByText('Alice')).toBeInTheDocument()
  })

  it('does not show sender name for own messages', () => {
    renderWithProviders(<MessageBubble message={mockMessage()} isOwn={true} />)
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  it('falls back to "Deleted User" when senderDisplayName is null', () => {
    renderWithProviders(
      <MessageBubble message={mockMessage({ senderDisplayName: null })} isOwn={false} />,
    )
    expect(screen.getByText('Deleted User')).toBeInTheDocument()
  })

  // ─── Deleted tombstone ────────────────────────────────────────────

  it('shows tombstone text when message is deleted', () => {
    renderWithProviders(<MessageBubble message={mockMessage({ deleted: true })} isOwn={false} />)
    expect(screen.getByText('This message was deleted')).toBeInTheDocument()
  })

  it('does not show content when message is deleted', () => {
    renderWithProviders(<MessageBubble message={mockMessage({ deleted: true })} isOwn={false} />)
    expect(screen.queryByText('Hello everyone!')).not.toBeInTheDocument()
  })

  it('does not show sender name when message is deleted', () => {
    renderWithProviders(<MessageBubble message={mockMessage({ deleted: true })} isOwn={false} />)
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  // ─── Optimistic state ─────────────────────────────────────────────

  it('renders at reduced opacity when id starts with "optimistic-"', () => {
    const optimisticMsg = mockMessage({ id: 'optimistic-abc123' })
    const { container } = renderWithProviders(
      <MessageBubble message={optimisticMsg} isOwn={true} />,
    )
    // The bubble div should have opacity-60 class
    expect(container.querySelector('.opacity-60')).toBeInTheDocument()
  })

  it('renders at full opacity when message has a real id', () => {
    const { container } = renderWithProviders(
      <MessageBubble message={mockMessage()} isOwn={true} />,
    )
    expect(container.querySelector('.opacity-60')).not.toBeInTheDocument()
    expect(container.querySelector('.opacity-100')).toBeInTheDocument()
  })

  it('renders at reduced opacity when id is empty string (pre-confirmed)', () => {
    const { container } = renderWithProviders(
      <MessageBubble message={mockMessage({ id: '' })} isOwn={true} />,
    )
    expect(container.querySelector('.opacity-60')).toBeInTheDocument()
  })

  // ─── Timestamp ────────────────────────────────────────────────────

  it('renders a formatted timestamp', () => {
    renderWithProviders(<MessageBubble message={mockMessage()} isOwn={false} />)
    // The message createdAt is '2025-07-01T10:00:00.000Z' — should produce a time string
    // We just assert something time-like is rendered (locale-aware)
    const timeEl = screen.getByText(/\d{1,2}:\d{2}\s*(AM|PM)/i)
    expect(timeEl).toBeInTheDocument()
  })
})
