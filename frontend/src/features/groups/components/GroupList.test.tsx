import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import { mockGroup, mockAdminGroup } from '@/test/handlers/groups'
import GroupList from './GroupList'

const noop = () => {}

describe('GroupList', () => {
  // ─── Empty state ──────────────────────────────────────────────────

  it('shows empty state when groups array is empty', () => {
    renderWithProviders(<GroupList groups={[]} leavingGroupId={null} onLeave={noop} deletingGroupId={null} onDelete={noop} />)
    expect(screen.getByText('No trips yet')).toBeInTheDocument()
    expect(screen.getByText(/create a group or join one/i)).toBeInTheDocument()
  })

  it('does not render any group cards when groups array is empty', () => {
    renderWithProviders(<GroupList groups={[]} leavingGroupId={null} onLeave={noop} deletingGroupId={null} onDelete={noop} />)
    expect(screen.queryByText('Paris Trip 2025')).not.toBeInTheDocument()
  })

  // ─── Populated list ───────────────────────────────────────────────

  it('renders a card for each group', () => {
    renderWithProviders(
      <GroupList groups={[mockGroup, mockAdminGroup]} leavingGroupId={null} onLeave={noop} deletingGroupId={null} onDelete={noop} />,
    )
    expect(screen.getByText('Paris Trip 2025')).toBeInTheDocument()
    expect(screen.getByText('Rome Trip')).toBeInTheDocument()
  })

  it('does not show empty state when groups are present', () => {
    renderWithProviders(
      <GroupList groups={[mockGroup]} leavingGroupId={null} onLeave={noop} deletingGroupId={null} onDelete={noop} />,
    )
    expect(screen.queryByText('No trips yet')).not.toBeInTheDocument()
  })

  // ─── isLeaving passthrough ────────────────────────────────────────

  it('disables the leave button for the group that is being left', () => {
    renderWithProviders(
      <GroupList groups={[mockGroup, mockAdminGroup]} leavingGroupId="group-1" onLeave={noop} deletingGroupId={null} onDelete={noop} />,
    )
    // Paris Trip (group-1) leave button should be disabled
    expect(screen.getByRole('button', { name: /leave paris trip 2025/i })).toBeDisabled()
  })

  it('keeps other leave buttons enabled while one group is being left', () => {
    const memberGroup2 = { ...mockGroup, id: 'group-3', name: 'Tokyo Trip', unreadCount: 0 }
    renderWithProviders(
      <GroupList
        groups={[mockGroup, memberGroup2]}
        leavingGroupId="group-1"
        onLeave={noop}
        deletingGroupId={null}
        onDelete={noop}
      />,
    )
    // group-3 should not be disabled
    expect(screen.getByRole('button', { name: /leave tokyo trip/i })).not.toBeDisabled()
  })
})
