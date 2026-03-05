import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '@/store/authStore'
import type { GroupResponse } from '@/types'
import { groupsApi } from '../api'
import type { CreateGroupFormData } from '../schemas'

export function useCreateGroup() {
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)

  return useMutation({
    mutationFn: (data: CreateGroupFormData) => groupsApi.createGroup(data),

    onMutate: async (variables) => {
      // Cancel outgoing refetches so they don't overwrite our optimistic entry.
      await queryClient.cancelQueries({ queryKey: ['groups'] })

      // Snapshot for rollback on error.
      const previousGroups = queryClient.getQueryData<GroupResponse[]>(['groups'])

      // Stable temp ID stored in context — used in onSuccess to find and replace
      // the optimistic entry with the real server response.
      const tempId = `optimistic-${Date.now()}`

      const optimisticGroup: GroupResponse = {
        id: tempId,
        name: variables.name,
        description: variables.description ?? null,
        inviteCode: '--------',
        memberCount: 1,
        myRole: 'ADMIN',
        createdBy: user?.id ?? '',
        createdAt: new Date().toISOString(),
        unreadCount: 0,
      }

      queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) => [
        optimisticGroup,
        ...old,
      ])

      return { previousGroups, tempId }
    },

    onError: (_err, _variables, context) => {
      if (context?.previousGroups) {
        queryClient.setQueryData(['groups'], context.previousGroups)
      }
    },

    onSuccess: (newGroup, _variables, context) => {
      // Replace the optimistic placeholder with the confirmed server response.
      queryClient.setQueryData<GroupResponse[]>(['groups'], (old = []) =>
        old.map((g) => (g.id === context.tempId ? newGroup : g)),
      )
    },
  })
}
