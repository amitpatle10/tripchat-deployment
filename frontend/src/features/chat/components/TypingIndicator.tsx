import type { TypingPayload } from '@/types'

interface TypingIndicatorProps {
  typingUsers: Record<string, TypingPayload>
}

// Pure presenter — derives display text from the map, renders nothing when empty.
export default function TypingIndicator({ typingUsers }: TypingIndicatorProps) {
  const names = Object.values(typingUsers).map((u) => u.displayName)

  if (names.length === 0) return null

  let text: string
  if (names.length === 1) text = `${names[0]} is typing...`
  else if (names.length === 2) text = `${names[0]} and ${names[1]} are typing...`
  else text = 'Several people are typing...'

  return (
    <div className="flex items-center gap-2 px-4 py-1.5">
      {/* Animated bounce dots */}
      <div className="flex items-center gap-0.5">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="w-1 h-1 rounded-full bg-indigo-400 animate-bounce"
            style={{ animationDelay: `${i * 150}ms` }}
          />
        ))}
      </div>
      <p className="text-xs text-gray-400">{text}</p>
    </div>
  )
}
