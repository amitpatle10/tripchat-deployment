import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { GroupResponse } from '@/types'

interface GroupCardProps {
  group: GroupResponse
  onLeave: (id: string) => void
  isLeaving: boolean
  onDelete: (id: string) => void
  isDeleting: boolean
}

// Presenter — receives data via props, fires callbacks upward.
// No hooks, no mutations. Independently testable with just a GroupResponse prop.
export default function GroupCard({ group, onLeave, isLeaving, onDelete, isDeleting }: GroupCardProps) {
  const navigate = useNavigate()
  const [copied, setCopied] = useState(false)
  // Two-step confirmation — first click arms it, second click fires.
  const [confirmDelete, setConfirmDelete] = useState(false)

  function copyInviteCode(e: React.MouseEvent) {
    e.stopPropagation()
    navigator.clipboard.writeText(group.inviteCode).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div
      onClick={() => navigate(`/groups/${group.id}`)}
      className="bg-gray-900 border border-gray-800 rounded-xl p-4 flex items-center gap-4 cursor-pointer hover:border-indigo-500/50 hover:bg-gray-800/60 transition"
    >
      {/* Avatar — first letter of group name */}
      <div className="w-11 h-11 rounded-xl bg-indigo-600/20 border border-indigo-500/30 flex items-center justify-center shrink-0 text-indigo-400 font-bold text-lg select-none">
        {group.name[0].toUpperCase()}
      </div>

      {/* Group info */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-white font-medium truncate">{group.name}</p>
          {group.myRole === 'ADMIN' && (
            <span className="text-xs text-indigo-400 bg-indigo-500/10 border border-indigo-500/20 rounded px-1.5 py-0.5 shrink-0">
              Admin
            </span>
          )}
          {group.myRole === 'ADMIN' && (
            <button
              onClick={copyInviteCode}
              title="Copy invite code"
              className="ml-auto flex items-center gap-1 text-xs font-mono text-gray-400 bg-gray-800 border border-gray-700 rounded px-1.5 py-0.5 hover:border-indigo-500/50 hover:text-indigo-300 transition shrink-0"
            >
              {copied ? (
                <span className="text-green-400">Copied!</span>
              ) : (
                <>
                  {group.inviteCode}
                  <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                  </svg>
                </>
              )}
            </button>
          )}
        </div>
        <p className="text-gray-400 text-sm truncate">
          {group.description ?? `${group.memberCount} member${group.memberCount !== 1 ? 's' : ''}`}
        </p>
      </div>

      {/* Right side — unread badge + action buttons.
          stopPropagation so clicking these doesn't navigate into the chat. */}
      <div
        className="flex items-center gap-2 shrink-0"
        onClick={(e) => e.stopPropagation()}
      >
        {group.unreadCount > 0 && !confirmDelete && (
          <span className="min-w-5 h-5 rounded-full bg-indigo-600 text-white text-xs font-medium flex items-center justify-center px-1.5">
            {group.unreadCount > 99 ? '99+' : group.unreadCount}
          </span>
        )}

        {/* ADMIN: delete group with two-step confirmation */}
        {group.myRole === 'ADMIN' && (
          confirmDelete ? (
            <div className="flex items-center gap-1.5">
              <span className="text-xs text-red-400">Delete group?</span>
              <button
                onClick={() => { onDelete(group.id); setConfirmDelete(false) }}
                disabled={isDeleting}
                className="text-xs bg-red-600 hover:bg-red-500 text-white px-2 py-0.5 rounded disabled:opacity-40 transition"
              >
                Yes
              </button>
              <button
                onClick={() => setConfirmDelete(false)}
                className="text-xs text-gray-400 hover:text-white px-2 py-0.5 rounded border border-gray-700 hover:border-gray-600 transition"
              >
                No
              </button>
            </div>
          ) : (
            <button
              onClick={() => setConfirmDelete(true)}
              disabled={isDeleting}
              className="text-gray-500 hover:text-red-400 disabled:opacity-40 transition p-1 rounded"
              aria-label={`Delete ${group.name}`}
              title="Delete group"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <polyline points="3 6 5 6 21 6" />
                <path strokeLinecap="round" strokeLinejoin="round" d="M19 6l-1 14H6L5 6M10 11v6M14 11v6M9 6V4h6v2" />
              </svg>
            </button>
          )
        )}

        {/* MEMBER: leave button */}
        {group.myRole === 'MEMBER' && (
          <button
            onClick={() => onLeave(group.id)}
            disabled={isLeaving}
            className="text-gray-500 hover:text-red-400 disabled:opacity-40 transition p-1 rounded"
            aria-label={`Leave ${group.name}`}
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
          </button>
        )}
      </div>
    </div>
  )
}
