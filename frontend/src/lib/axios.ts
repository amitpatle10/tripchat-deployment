import axios from 'axios'
import { useAuthStore } from '@/store/authStore'

const api = axios.create({
  baseURL: '/api/v1', // proxied to http://localhost:8080 by Vite in dev
  // Prevent the browser's HTTP cache from serving stale API responses.
  // TanStack Query owns our caching strategy — browser disk cache is redundant
  // and causes "e.map is not a function" crashes when a cached response has
  // a different shape than the current API contract.
  headers: { 'Cache-Control': 'no-cache' },
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
      window.location.replace('/login')
    }
    return Promise.reject(error)
  },
)

export default api
