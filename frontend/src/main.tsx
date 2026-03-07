import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/queryClient'
import { router } from '@/lib/router'
import ErrorBoundary from '@/components/ErrorBoundary'
import './index.css'

// ── Global non-React error listeners ─────────────────────────────────────────
// These cover JS errors and unhandled promise rejections that occur outside
// the React component tree (e.g. a bare fetch(), a setTimeout callback).
// They log and return false — the app keeps running. If a component later
// throws during render, the ErrorBoundary above will catch it and show the
// fun fallback screen.
window.addEventListener('unhandledrejection', (event) => {
  console.error('[unhandledrejection]', event.reason)
})

window.onerror = (message, source, line, col, error) => {
  console.error('[window.onerror]', { message, source, line, col, error })
  return false // let the browser continue; do not suppress the error in DevTools
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
