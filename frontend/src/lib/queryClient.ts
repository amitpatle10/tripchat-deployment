import { QueryClient } from '@tanstack/react-query'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60,      // 1 min — data is fresh, no background refetch
      gcTime: 1000 * 60 * 5,    // 5 min — unused cache entries are garbage collected
      retry: 1,                  // one retry on failure, then surface the error
      refetchOnWindowFocus: true, // refetch when tab regains focus (groups/unread may have changed)
    },
    mutations: {
      retry: 0, // never retry mutations — user action should not fire twice silently
    },
  },
})
