import { screen } from '@testing-library/react'
import { renderWithProviders } from '@/test/utils'
import JoinGroupModal from './JoinGroupModal'

const onClose = vi.fn()

describe('JoinGroupModal', () => {
  beforeEach(() => {
    onClose.mockClear()
  })

  // ─── Visibility ───────────────────────────────────────────────────

  it('renders nothing when open is false', () => {
    renderWithProviders(<JoinGroupModal open={false} onClose={onClose} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('renders the dialog when open is true', () => {
    renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('Join a group')).toBeInTheDocument()
  })

  // ─── Validation ───────────────────────────────────────────────────

  it('shows validation error when invite code is too short', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Invite code'), 'ABC')
    await user.click(screen.getByRole('button', { name: /join group/i }))

    expect(await screen.findByText(/8 characters/i)).toBeInTheDocument()
  })

  it('shows validation error when invite code is empty', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: /join group/i }))

    expect(await screen.findByText(/8 characters/i)).toBeInTheDocument()
  })

  // ─── Server errors ────────────────────────────────────────────────

  it('shows field error when invite code is not found (404)', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Invite code'), 'NOTFOUND')
    await user.click(screen.getByRole('button', { name: /join group/i }))

    // MSW handler returns 404 for 'NOTFOUND'
    expect(await screen.findByText('Invite code not found')).toBeInTheDocument()
  })

  it('shows field error when already a member (409)', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Invite code'), 'MEMBER01')
    await user.click(screen.getByRole('button', { name: /join group/i }))

    // MSW handler returns 409 for 'MEMBER01'
    expect(await screen.findByText(/already a member/i)).toBeInTheDocument()
  })

  // ─── Happy path ───────────────────────────────────────────────────

  it('calls onClose after successfully joining', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Invite code'), 'AB12CD34')
    await user.click(screen.getByRole('button', { name: /join group/i }))

    await screen.findByRole('button', { name: /join group/i })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // ─── Cancel ───────────────────────────────────────────────────────

  it('calls onClose when Cancel button is clicked', async () => {
    const { user } = renderWithProviders(<JoinGroupModal open={true} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: /cancel/i }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
