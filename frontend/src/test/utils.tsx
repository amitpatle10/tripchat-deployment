import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'

// Creates a fresh QueryClient per test — prevents cache bleed between tests.
// retry: 0 is critical: without it TanStack Query retries failed requests
// and tests hang waiting for retries to exhaust.
function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: 0 },
      mutations: { retry: 0 },
    },
  })
}

// Wraps a component with all providers needed to render in tests.
// Returns RTL's render result + a pre-configured userEvent instance.
export function renderWithProviders(ui: ReactElement) {
  const testQueryClient = createTestQueryClient()

  const result = render(
    <QueryClientProvider client={testQueryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )

  return {
    ...result,
    user: userEvent.setup(),
  }
}
