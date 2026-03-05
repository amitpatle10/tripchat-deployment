import api from '@/lib/axios'
import type { AuthResponse } from '@/types'
import type { LoginFormData, RegisterFormData } from './schemas'

export const authApi = {
  login: (data: LoginFormData) =>
    api.post<AuthResponse>('/auth/login', data).then((r) => r.data),

  register: (data: RegisterFormData) =>
    api.post<AuthResponse>('/auth/register', data).then((r) => r.data),
}
