import axios from 'axios'
import { useAuthStore } from '@/store/authStore'
import { router } from '@/lib/router'

const api = axios.create({
  baseURL: '/api/v1', // proxied to http://localhost:8080 by Vite in dev
})

// Attach JWT to every request.
// Reads directly from Zustand store — no React hook needed outside components.
api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Handle 401 globally — token expired or invalid.
// Clear auth state and redirect to /login without a page reload.
api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      useAuthStore.getState().clearAuth()
      router.navigate('/login', { replace: true })
    }
    return Promise.reject(error)
  },
)

export default api
