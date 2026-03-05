import { z } from 'zod'

export const loginSchema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
})

export const registerSchema = z.object({
  displayName: z.string().min(2, 'At least 2 characters').max(30, 'Max 30 characters'),
  username: z
    .string()
    .min(3, 'At least 3 characters')
    .max(50, 'Max 50 characters')
    .regex(/^[a-zA-Z0-9_]+$/, 'Letters, digits and underscores only'),
  email: z.string().email('Enter a valid email'),
  password: z
    .string()
    .min(8, 'At least 8 characters')
    .regex(/[0-9]/, 'Must contain at least one digit')
    .regex(/[@$!%*?&]/, 'Must contain at least one special character (@$!%*?&)'),
})

export type LoginFormData = z.infer<typeof loginSchema>
export type RegisterFormData = z.infer<typeof registerSchema>
