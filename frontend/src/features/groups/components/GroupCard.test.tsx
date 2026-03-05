import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import { mockGroup, mockAdminGroup } from '@/test/handlers/groups'
import GroupCard from './GroupCard'

const noop = () => {}

describe('GroupCard', () => {
  // ─── Display ──────────────────────────────────────────────────────

  it('renders the group name', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByText('Paris Trip 2025')).toBeInTheDocument()
  })

  it('renders description when present', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByText('Planning our summer trip')).toBeInTheDocument()
  })

  it('renders member count when description is null', () => {
    renderWithProviders(<GroupCard group={mockAdminGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByText('5 members')).toBeInTheDocument()
  })

  it('shows the first letter of the group name as avatar', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByText('P')).toBeInTheDocument()
  })

  // ─── Unread badge ─────────────────────────────────────────────────

  it('shows unread badge when unreadCount > 0', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    // mockGroup has unreadCount: 3
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('does not show unread badge when unreadCount is 0', () => {
    renderWithProviders(<GroupCard group={mockAdminGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    // mockAdminGroup has unreadCount: 0
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  it('shows 99+ when unreadCount exceeds 99', () => {
    renderWithProviders(
      <GroupCard group={{ ...mockGroup, unreadCount: 150 }} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />,
    )
    expect(screen.getByText('99+')).toBeInTheDocument()
  })

  // ─── Role — Admin badge & leave button ───────────────────────────

  it('shows Admin badge for ADMIN role', () => {
    renderWithProviders(<GroupCard group={mockAdminGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByText('Admin')).toBeInTheDocument()
  })

  it('does not show Admin badge for MEMBER role', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.queryByText('Admin')).not.toBeInTheDocument()
  })

  it('shows leave button for MEMBER', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.getByRole('button', { name: /leave paris trip 2025/i })).toBeInTheDocument()
  })

  it('does not show leave button for ADMIN', () => {
    renderWithProviders(<GroupCard group={mockAdminGroup} onLeave={noop} isLeaving={false} onDelete={noop} isDeleting={false} />)
    expect(screen.queryByRole('button', { name: /leave/i })).not.toBeInTheDocument()
  })

  // ─── Leave button interaction ─────────────────────────────────────

  it('calls onLeave with the group id when leave button is clicked', async () => {
    const onLeave = vi.fn()
    const { user } = renderWithProviders(
      <GroupCard group={mockGroup} onLeave={onLeave} isLeaving={false} onDelete={noop} isDeleting={false} />,
    )

    await user.click(screen.getByRole('button', { name: /leave paris trip 2025/i }))

    expect(onLeave).toHaveBeenCalledWith('group-1')
  })

  it('disables leave button while isLeaving is true', () => {
    renderWithProviders(<GroupCard group={mockGroup} onLeave={noop} isLeaving={true} onDelete={noop} isDeleting={false} />)
    expect(screen.getByRole('button', { name: /leave/i })).toBeDisabled()
  })
})
