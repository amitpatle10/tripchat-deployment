import { useMutation, useQueryClient } from '@tanstack/react-query'
import { groupsApi } from '../api'

// Join uses invalidation, not optimistic update — we don't know which group
// we're joining until the server resolves the invite code.
export function useJoinGroup() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (inviteCode: string) => groupsApi.joinGroup(inviteCode),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}
