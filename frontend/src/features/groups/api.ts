import api from '@/lib/axios'
import type { GroupMemberResponse, GroupResponse } from '@/types'

export const groupsApi = {
  getMyGroups: () =>
    api.get<GroupResponse[]>('/groups').then((r) => r.data),

  createGroup: (data: { name: string; description?: string }) =>
    api.post<GroupResponse>('/groups', data).then((r) => r.data),

  joinGroup: (inviteCode: string) =>
    api.post<GroupResponse>('/groups/join', { inviteCode }).then((r) => r.data),

  leaveGroup: (groupId: string) =>
    api.delete(`/groups/${groupId}/leave`),

  deleteGroup: (groupId: string) =>
    api.delete(`/groups/${groupId}`),

  getGroupMembers: (groupId: string) =>
    api.get<GroupMemberResponse[]>(`/groups/${groupId}/members`).then((r) => r.data),
}
