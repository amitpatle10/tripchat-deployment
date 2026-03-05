import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '@/test/utils'
import { server } from '@/test/mswServer'
import CreateGroupModal from './CreateGroupModal'

const onClose = vi.fn()

describe('CreateGroupModal', () => {
  beforeEach(() => {
    onClose.mockClear()
  })

  // ─── Visibility ───────────────────────────────────────────────────

  it('renders nothing when open is false', () => {
    renderWithProviders(<CreateGroupModal open={false} onClose={onClose} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('renders the dialog when open is true', () => {
    renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('New group')).toBeInTheDocument()
  })

  // ─── Validation ───────────────────────────────────────────────────

  it('shows error when submitting with an empty name', async () => {
    const { user } = renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: /create group/i }))

    expect(await screen.findByText(/at least 3 characters/i)).toBeInTheDocument()
  })

  it('shows error when name is too short (under 3 chars)', async () => {
    const { user } = renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Group name'), 'AB')
    await user.click(screen.getByRole('button', { name: /create group/i }))

    expect(await screen.findByText(/at least 3 characters/i)).toBeInTheDocument()
  })

  // ─── Loading state ────────────────────────────────────────────────

  it('disables submit and shows "Creating..." while request is in flight', async () => {
    server.use(
      http.post('/api/v1/groups', async () => {
        await new Promise(() => {}) // never resolves
        return HttpResponse.json({})
      }),
    )
    const { user } = renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Group name'), 'Paris Trip')
    await user.click(screen.getByRole('button', { name: /create group/i }))

    expect(await screen.findByRole('button', { name: /creating/i })).toBeDisabled()
  })

  // ─── Happy path ───────────────────────────────────────────────────

  it('calls onClose after a successful create', async () => {
    const { user } = renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)

    await user.type(screen.getByLabelText('Group name'), 'Paris Trip')
    await user.click(screen.getByRole('button', { name: /create group/i }))

    // Wait for the mutation to settle
    await screen.findByRole('button', { name: /create group/i })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  // ─── Cancel / backdrop ────────────────────────────────────────────

  it('calls onClose when Cancel button is clicked', async () => {
    const { user } = renderWithProviders(<CreateGroupModal open={true} onClose={onClose} />)

    await user.click(screen.getByRole('button', { name: /cancel/i }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
