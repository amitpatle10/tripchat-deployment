import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { GroupResponse } from '@/types'
import { groupsApi } from '../api'

export function useDeleteGroup() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (groupId: string) => groupsApi.deleteGroup(groupId),

    // Optimistic removal — group disappears from the list immediately.
    onMutate: async (groupId) => {
      await queryClient.cancelQueries({ queryKey: ['groups'] })

      const previousGroups = queryClient.getQueryData<GroupResponse[]>(['groups'])

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

    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] })
    },
  })
}
