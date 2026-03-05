import { useQuery } from '@tanstack/react-query'
import { chatApi } from '../api'

// Fetches online members once when the chat page opens.
// staleTime: Infinity prevents background refetches — presence is a snapshot,
// not a live feed. The server's Redis TTL handles the ground truth.
export function usePresence(groupId: string) {
  return useQuery({
    queryKey: ['presence', groupId],
    queryFn: () => chatApi.getPresence(groupId),
    staleTime: Infinity,
  })
}
