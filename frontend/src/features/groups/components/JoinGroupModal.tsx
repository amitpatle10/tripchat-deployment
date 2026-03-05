import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import axios from 'axios'
import { joinGroupSchema, type JoinGroupFormData } from '../schemas'
import { useJoinGroup } from '../hooks/useJoinGroup'

interface JoinGroupModalProps {
  open: boolean
  onClose: () => void
}

export default function JoinGroupModal({ open, onClose }: JoinGroupModalProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setError,
  } = useForm<JoinGroupFormData>({
    resolver: zodResolver(joinGroupSchema),
  })

  const { mutate: joinGroup, isPending } = useJoinGroup()

  useEffect(() => {
    if (open) reset()
  }, [open, reset])

  const onSubmit = (data: JoinGroupFormData) => {
    joinGroup(data.inviteCode, {
      onSuccess: () => onClose(),
      onError: (error) => {
        if (axios.isAxiosError(error)) {
          if (error.response?.status === 404) {
            setError('inviteCode', { message: 'Invite code not found' })
          } else if (error.response?.status === 409) {
            setError('inviteCode', { message: "You're already a member of this group" })
          } else {
            setError('inviteCode', { message: 'Something went wrong. Please try again.' })
          }
        }
      },
    })
  }

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="join-group-title"
    >
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />

      <div className="relative w-full max-w-md bg-gray-900 rounded-2xl border border-gray-800 p-6 shadow-xl">
        <h2 id="join-group-title" className="text-lg font-semibold text-white mb-5">
          Join a group
        </h2>

        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          <div className="mb-6">
            <label htmlFor="inviteCode" className="block text-sm font-medium text-gray-300 mb-1.5">
              Invite code
            </label>
            <input
              id="inviteCode"
              type="text"
              placeholder="AB12CD34"
              maxLength={8}
              autoFocus
              {...register('inviteCode')}
              className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition tracking-widest uppercase"
              aria-invalid={!!errors.inviteCode}
              aria-describedby={errors.inviteCode ? 'code-error' : undefined}
            />
            {errors.inviteCode && (
              <p id="code-error" className="text-red-400 text-xs mt-1">
                {errors.inviteCode.message}
              </p>
            )}
          </div>

          <div className="flex items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="text-sm text-gray-400 hover:text-white transition px-4 py-2"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isPending}
              className="bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 disabled:cursor-not-allowed text-white font-medium rounded-lg px-5 py-2.5 text-sm transition"
            >
              {isPending ? 'Joining...' : 'Join group'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
