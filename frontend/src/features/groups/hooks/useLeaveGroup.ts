import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { GroupResponse } from '@/types'
import { groupsApi } from '../api'

export function useLeaveGroup() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (groupId: string) => groupsApi.leaveGroup(groupId),

    onMutate: async (groupId) => {
      await queryClient.cancelQueries({ queryKey: ['groups'] })

      const previousGroups = queryClient.getQueryData<GroupResponse[]>(['groups'])

      // Optimistically remove from the list immediately.
      queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) =>
        old.filter((g) => g.id !== groupId),
      )

      return { previousGroups }
    },

    onError: (_err, _variables, context) => {
      if (context?.previousGroups) {
        queryClient.setQueryData(['groups'], context.previousGroups)
      }
    },

    // Refetch after settle to sync with server truth (e.g. admin-cannot-leave 400).
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}
