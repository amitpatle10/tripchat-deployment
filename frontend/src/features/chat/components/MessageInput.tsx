import { useState, useRef, useEffect } from 'react'

interface MessageInputProps {
  onSend: (content: string) => void
  sendTyping: (typing: boolean) => void
}

export default function MessageInput({ onSend, sendTyping }: MessageInputProps) {
  const [content, setContent] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Refs for typing state — avoids stale closures in setInterval and cleanup.
  const isTypingRef = useRef(false)
  const typingIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  // Always holds the latest sendTyping prop — safe to call from cleanup effect.
  const sendTypingRef = useRef(sendTyping)
  sendTypingRef.current = sendTyping

  const startTyping = () => {
    if (isTypingRef.current) return
    isTypingRef.current = true
    sendTypingRef.current(true)
    // Refresh every 3s to keep the server's 5s TTL alive.
    typingIntervalRef.current = setInterval(() => {
      sendTypingRef.current(true)
    }, 3000)
  }

  const stopTyping = () => {
    if (!isTypingRef.current) return
    isTypingRef.current = false
    sendTypingRef.current(false)
    if (typingIntervalRef.current) {
      clearInterval(typingIntervalRef.current)
      typingIntervalRef.current = null
    }
  }

  // Send typing: false when the component unmounts (user navigates away).
  // Uses refs so the cleanup never captures stale values.
  useEffect(() => {
    return () => {
      if (isTypingRef.current) sendTypingRef.current(false)
      if (typingIntervalRef.current) clearInterval(typingIntervalRef.current)
    }
  }, [])

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value
    setContent(value)
    // Auto-grow the textarea up to 128px (8 lines approx)
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
      textareaRef.current.style.height = `${Math.min(textareaRef.current.scrollHeight, 128)}px`
    }
    value.trim() ? startTyping() : stopTyping()
  }

  const handleSend = () => {
    const trimmed = content.trim()
    if (!trimmed) return
    onSend(trimmed)
    setContent('')
    stopTyping()
    // Reset textarea height after clearing
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }

  // Enter sends, Shift+Enter inserts a newline.
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="border-t border-gray-800 px-4 py-3 flex items-end gap-3">
      <textarea
        ref={textareaRef}
        value={content}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        placeholder="Message... (Enter to send, Shift+Enter for new line)"
        rows={1}
        className="flex-1 bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-xl px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition resize-none overflow-y-hidden"
        aria-label="Message input"
      />
      <button
        onClick={handleSend}
        disabled={!content.trim()}
        className="w-9 h-9 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center transition shrink-0"
        aria-label="Send message"
      >
        <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.269 20.876L5.999 12zm0 0h7.5" />
        </svg>
      </button>
    </div>
  )
}
