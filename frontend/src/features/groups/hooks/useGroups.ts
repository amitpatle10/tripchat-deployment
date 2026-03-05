import { useQuery } from '@tanstack/react-query'
import { groupsApi } from '../api'

export function useGroups() {
  return useQuery({
    queryKey: ['groups'],
    queryFn: groupsApi.getMyGroups,
  })
}
