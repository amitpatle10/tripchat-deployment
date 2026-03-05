import type { GroupResponse } from '@/types'
import { useGroupMembers } from '../hooks/useGroupMembers'

interface GroupInfoPanelProps {
  group: GroupResponse
  onClose: () => void
}

export default function GroupInfoPanel({ group, onClose }: GroupInfoPanelProps) {
  const { data: members, isLoading } = useGroupMembers(group.id)

  const createdAt = new Date(group.createdAt).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })

  return (
    // Overlay — clicking the backdrop closes the panel
    <div
      className="fixed inset-0 z-50 flex justify-end"
      onClick={onClose}
    >
      {/* Panel — stop click from bubbling to overlay */}
      <div
        className="w-72 h-full bg-gray-900 border-l border-gray-800 flex flex-col shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-4 border-b border-gray-800 shrink-0">
          <h2 className="text-white font-semibold text-sm">Group info</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-white transition p-1 rounded"
            aria-label="Close panel"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Group details */}
        <div className="px-4 py-4 border-b border-gray-800 shrink-0">
          {/* Avatar */}
          <div className="w-14 h-14 rounded-full bg-indigo-600/30 flex items-center justify-center text-2xl text-indigo-300 font-bold mb-3">
            {group.name[0].toUpperCase()}
          </div>

          <p className="text-white font-semibold text-base leading-snug">{group.name}</p>

          {group.description && (
            <p className="text-gray-400 text-sm mt-1 leading-snug">{group.description}</p>
          )}

          <p className="text-gray-500 text-xs mt-2">Created {createdAt}</p>
          <p className="text-gray-500 text-xs mt-0.5">{group.memberCount} members</p>
        </div>

        {/* Members list */}
        <div className="flex-1 overflow-y-auto">
          <p className="px-4 pt-4 pb-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">
            Members
          </p>

          {isLoading && (
            <div className="px-4 py-2 text-sm text-gray-500">Loading...</div>
          )}

          {members?.map((member) => (
            <div
              key={member.userId}
              className="flex items-center gap-3 px-4 py-2.5 hover:bg-gray-800/50 transition"
            >
              {/* Avatar */}
              <div className="w-8 h-8 rounded-full bg-indigo-600/30 flex items-center justify-center text-sm text-indigo-300 font-medium shrink-0">
                {member.displayName[0].toUpperCase()}
              </div>

              {/* Name + username */}
              <div className="flex-1 min-w-0">
                <p className="text-sm text-white font-medium truncate leading-tight">
                  {member.displayName}
                </p>
                <p className="text-xs text-gray-500 truncate leading-tight">
                  @{member.username}
                </p>
              </div>

              {/* Admin badge */}
              {member.role === 'ADMIN' && (
                <span className="text-xs bg-indigo-600/20 text-indigo-400 px-1.5 py-0.5 rounded font-medium shrink-0">
                  Admin
                </span>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
