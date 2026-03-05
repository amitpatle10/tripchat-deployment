import { z } from 'zod'

export const createGroupSchema = z.object({
  name: z.string().min(3, 'At least 3 characters').max(50, 'Max 50 characters'),
  description: z.string().max(500, 'Max 500 characters').optional(),
})

export const joinGroupSchema = z.object({
  inviteCode: z.string().length(8, 'Invite code must be exactly 8 characters'),
})

export type CreateGroupFormData = z.infer<typeof createGroupSchema>
export type JoinGroupFormData = z.infer<typeof joinGroupSchema>
