import { Component, type ErrorInfo, type ReactNode } from 'react'

// ── ErrorFallback ─────────────────────────────────────────────────────────────
// Pure presenter — receives no props, just renders the fun screen.
// Named export so router.tsx can use it as errorElement on each route,
// which covers errors thrown inside React Router's own component tree
// (React Router intercepts those before they reach the class ErrorBoundary).
export function ErrorFallback() {
  return (
    <div className="min-h-screen bg-gray-950 flex flex-col items-center justify-center px-6 text-center">

      {/* Plane illustration */}
      <div className="text-8xl mb-6 select-none animate-bounce">✈️</div>

      <h1 className="text-3xl font-bold text-white mb-3">
        Oops, we hit some turbulence!
      </h1>

      <p className="text-gray-400 text-lg mb-2 max-w-md">
        Something unexpected happened on our end. We're sorting it out.
      </p>

      <p className="text-indigo-400 text-base mb-10 max-w-sm">
        Meanwhile — close your eyes and picture your next adventure. 🌍
      </p>

      {/* Destination ideas to keep it fun */}
      <div className="flex gap-4 mb-10 text-2xl select-none">
        {['🗼', '🏝️', '🏔️', '🗽', '🏯'].map((emoji, i) => (
          <span
            key={i}
            className="opacity-60 hover:opacity-100 transition-opacity duration-300 cursor-default"
            title="Dream destination"
          >
            {emoji}
          </span>
        ))}
      </div>

      <button
        onClick={() => window.location.reload()}
        className="px-6 py-3 bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl font-medium transition-colors duration-150"
      >
        Take me back
      </button>

    </div>
  )
}

// ── ErrorBoundary ─────────────────────────────────────────────────────────────
// Class component — getDerivedStateFromError + componentDidCatch are class-only
// lifecycle methods. There is no hook equivalent.
//
// Pattern: Proxy. Sits transparently in the tree, invisible during normal
// operation. Intercepts only when a descendant throws during render / lifecycle.
//
// Placement: wraps the entire app in main.tsx so no crash can reach the user
// as a blank white screen.
interface State {
  hasError: boolean
}

interface Props {
  children: ReactNode
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    // Flip the flag synchronously — React uses this to decide what to render.
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Log for debugging. Swap console.error for a real error-tracking service
    // (Sentry, Datadog) here when the time comes — one-line change.
    console.error('[ErrorBoundary] Uncaught render error:', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback />
    }
    return this.props.children
  }
}
