import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { createGroupSchema, type CreateGroupFormData } from '../schemas'
import { useCreateGroup } from '../hooks/useCreateGroup'

interface CreateGroupModalProps {
  open: boolean
  onClose: () => void
}

export default function CreateGroupModal({ open, onClose }: CreateGroupModalProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<CreateGroupFormData>({
    resolver: zodResolver(createGroupSchema),
  })

  const { mutate: createGroup, isPending } = useCreateGroup()

  // Reset validation state each time the modal opens.
  useEffect(() => {
    if (open) reset()
  }, [open, reset])

  const onSubmit = (data: CreateGroupFormData) => {
    createGroup(data, {
      onSuccess: () => onClose(),
    })
  }

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-group-title"
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />

      {/* Panel */}
      <div className="relative w-full max-w-md bg-gray-900 rounded-2xl border border-gray-800 p-6 shadow-xl">
        <h2 id="create-group-title" className="text-lg font-semibold text-white mb-5">
          New group
        </h2>

        <form onSubmit={handleSubmit(onSubmit)} noValidate>
          {/* Group name */}
          <div className="mb-4">
            <label htmlFor="name" className="block text-sm font-medium text-gray-300 mb-1.5">
              Group name
            </label>
            <input
              id="name"
              type="text"
              placeholder="Paris Trip 2025"
              autoFocus
              {...register('name')}
              className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition"
              aria-invalid={!!errors.name}
              aria-describedby={errors.name ? 'name-error' : undefined}
            />
            {errors.name && (
              <p id="name-error" className="text-red-400 text-xs mt-1">
                {errors.name.message}
              </p>
            )}
          </div>

          {/* Description (optional) */}
          <div className="mb-6">
            <label htmlFor="description" className="block text-sm font-medium text-gray-300 mb-1.5">
              Description{' '}
              <span className="text-gray-500 font-normal">(optional)</span>
            </label>
            <textarea
              id="description"
              placeholder="What's this trip about?"
              rows={3}
              {...register('description')}
              className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition resize-none"
              aria-invalid={!!errors.description}
              aria-describedby={errors.description ? 'desc-error' : undefined}
            />
            {errors.description && (
              <p id="desc-error" className="text-red-400 text-xs mt-1">
                {errors.description.message}
              </p>
            )}
          </div>

          {/* Actions */}
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
              {isPending ? 'Creating...' : 'Create group'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
