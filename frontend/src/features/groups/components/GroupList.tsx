import type { GroupResponse } from '@/types'
import GroupCard from './GroupCard'

interface GroupListProps {
  groups: GroupResponse[]
  leavingGroupId: string | null
  onLeave: (id: string) => void
  deletingGroupId: string | null
  onDelete: (id: string) => void
}

// Presenter — renders the list or an empty state. No data fetching.
export default function GroupList({ groups, leavingGroupId, onLeave, deletingGroupId, onDelete }: GroupListProps) {
  if (groups.length === 0) {
    return (
      <div className="text-center py-20">
        <p className="text-gray-300 font-medium">No trips yet</p>
        <p className="text-gray-500 text-sm mt-1">
          Create a group or join one with an invite code
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {groups.map((group) => (
        <GroupCard
          key={group.id}
          group={group}
          onLeave={onLeave}
          isLeaving={leavingGroupId === group.id}
          onDelete={onDelete}
          isDeleting={deletingGroupId === group.id}
        />
      ))}
    </div>
  )
}
