import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { GroupResponse, PresenceUser } from '@/types'
import GroupInfoPanel from '@/features/groups/components/GroupInfoPanel'

interface ChatHeaderProps {
  group: GroupResponse | undefined
  onlineUsers: PresenceUser[]
}

export default function ChatHeader({ group, onlineUsers }: ChatHeaderProps) {
  const navigate = useNavigate()
  const [showInfo, setShowInfo] = useState(false)

  return (
    <>
      <header className="border-b border-gray-800 px-4 py-3 flex items-center gap-3 bg-gray-950 shrink-0">

        {/* Back to groups list */}
        <button
          onClick={() => navigate('/')}
          className="text-gray-400 hover:text-white transition p-1 -ml-1 rounded-lg"
          aria-label="Back to groups"
        >
          <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </button>

        {/* Group name + subtitle */}
        <div className="flex-1 min-w-0">
          <p className="text-white font-semibold truncate">
            {group?.name ?? '—'}
          </p>
          <p className="text-xs text-gray-400">
            {onlineUsers.length > 0
              ? `${onlineUsers.length} online`
              : `${group?.memberCount ?? '—'} members`}
          </p>
        </div>

        {/* Online member avatars — first letter, up to 4, then overflow count */}
        {onlineUsers.length > 0 && (
          <div className="flex -space-x-1.5 shrink-0">
            {onlineUsers.slice(0, 4).map((u) => (
              <div
                key={u.userId}
                title={u.displayName}
                className="w-7 h-7 rounded-full bg-indigo-600/30 border-2 border-gray-950 flex items-center justify-center text-xs text-indigo-300 font-medium"
              >
                {u.displayName[0].toUpperCase()}
              </div>
            ))}
            {onlineUsers.length > 4 && (
              <div className="w-7 h-7 rounded-full bg-gray-700 border-2 border-gray-950 flex items-center justify-center text-xs text-gray-400">
                +{onlineUsers.length - 4}
              </div>
            )}
          </div>
        )}

        {/* Group info icon */}
        {group && (
          <button
            onClick={() => setShowInfo(true)}
            className="text-gray-400 hover:text-white transition p-1 rounded-lg shrink-0"
            aria-label="Group info"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M13 16h-1v-4h-1m1-4h.01M12 2a10 10 0 100 20A10 10 0 0012 2z" />
            </svg>
          </button>
        )}
      </header>

      {/* Slide-in info panel */}
      {showInfo && group && (
        <GroupInfoPanel group={group} onClose={() => setShowInfo(false)} />
      )}
    </>
  )
}
