import { useState } from 'react'
import { useAuthStore } from '@/store/authStore'
import { disconnectStomp } from '@/lib/stompClient'
import { useGroups } from '../hooks/useGroups'
import { useLeaveGroup } from '../hooks/useLeaveGroup'
import { useDeleteGroup } from '../hooks/useDeleteGroup'
import GroupList from './GroupList'
import CreateGroupModal from './CreateGroupModal'
import JoinGroupModal from './JoinGroupModal'

// Container — owns server state (useGroups), modal open/close flags, and leave
// orchestration. Passes data and callbacks down; renders no visual chrome itself.
export default function GroupListPage() {
  const [showCreate, setShowCreate] = useState(false)
  const [showJoin, setShowJoin] = useState(false)
  // Track which group is mid-leave so GroupCard can show a per-card disabled state.
  const [leavingGroupId, setLeavingGroupId] = useState<string | null>(null)
  const [deletingGroupId, setDeletingGroupId] = useState<string | null>(null)

  const { data: groups = [], isLoading, isError } = useGroups()
  const { mutate: leaveGroup } = useLeaveGroup()
  const { mutate: deleteGroup } = useDeleteGroup()

  const clearAuth = useAuthStore((s) => s.clearAuth)

  const handleLeave = (groupId: string) => {
    setLeavingGroupId(groupId)
    leaveGroup(groupId, {
      onSettled: () => setLeavingGroupId(null),
    })
  }

  const handleDelete = (groupId: string) => {
    setDeletingGroupId(groupId)
    deleteGroup(groupId, {
      onSettled: () => setDeletingGroupId(null),
    })
  }

  const handleLogout = () => {
    disconnectStomp()
    clearAuth()
    // ProtectedRoute detects no token and redirects to /login automatically.
  }

  return (
    <div className="min-h-screen bg-gray-950">

      {/* Header */}
      <header className="border-b border-gray-800 px-4 py-3 flex items-center justify-between sticky top-0 bg-gray-950/90 backdrop-blur-sm z-10">
        <div className="flex items-center gap-2 min-w-0">
          <div className="w-8 h-8 rounded-lg bg-indigo-600 flex items-center justify-center shrink-0">
            <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
            </svg>
          </div>
          <span className="text-white font-semibold truncate">TripChat</span>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={() => setShowJoin(true)}
            className="text-sm text-gray-400 hover:text-white border border-gray-700 hover:border-gray-600 rounded-lg px-3 py-1.5 transition"
          >
            Join
          </button>
          <button
            onClick={() => setShowCreate(true)}
            className="text-sm bg-indigo-600 hover:bg-indigo-500 text-white font-medium rounded-lg px-3 py-1.5 transition"
          >
            New group
          </button>
          <button
            onClick={handleLogout}
            className="text-gray-500 hover:text-white transition p-1.5 rounded-lg"
            aria-label="Log out"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
          </button>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-xl mx-auto px-4 py-6">
        <h1 className="text-xl font-bold text-white mb-4">Your trips</h1>

        {/* Skeleton loading state — 3 placeholder cards */}
        {isLoading && (
          <div className="space-y-2">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-[72px] bg-gray-900 rounded-xl border border-gray-800 animate-pulse" />
            ))}
          </div>
        )}

        {isError && (
          <p className="text-red-400 text-sm">Failed to load groups. Please refresh.</p>
        )}

        {!isLoading && !isError && (
          <GroupList
            groups={groups}
            leavingGroupId={leavingGroupId}
            onLeave={handleLeave}
            deletingGroupId={deletingGroupId}
            onDelete={handleDelete}
          />
        )}
      </main>

      <CreateGroupModal open={showCreate} onClose={() => setShowCreate(false)} />
      <JoinGroupModal open={showJoin} onClose={() => setShowJoin(false)} />
    </div>
  )
}
