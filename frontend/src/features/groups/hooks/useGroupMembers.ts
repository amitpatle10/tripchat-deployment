import { useQuery } from '@tanstack/react-query'
import { groupsApi } from '../api'

export function useGroupMembers(groupId: string | undefined) {
  return useQuery({
    queryKey: ['groups', groupId, 'members'],
    queryFn: () => groupsApi.getGroupMembers(groupId!),
    enabled: !!groupId,
  })
}
