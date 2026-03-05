import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import axios from 'axios'
import { registerSchema, type RegisterFormData } from '../schemas'
import { useRegister } from '../hooks/useRegister'
import type { ApiError } from '@/types'

export default function RegisterForm() {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterFormData>({
    resolver: zodResolver(registerSchema),
  })

  const { mutate: registerUser, isPending } = useRegister()

  const onSubmit = (data: RegisterFormData) => {
    registerUser(data, {
      onError: (error) => {
        if (!axios.isAxiosError(error)) {
          setError('root', { message: 'Something went wrong. Please try again.' })
          return
        }

        const status = error.response?.status
        const message: string = (error.response?.data as ApiError)?.message ?? ''

        if (status === 409) {
          // Backend sends "Email already taken" or "Username already taken".
          // Map to the specific field so the user knows exactly what to fix.
          if (message.toLowerCase().includes('email')) {
            setError('email', { message: 'This email is already taken' })
          } else if (message.toLowerCase().includes('username')) {
            setError('username', { message: 'This username is already taken' })
          } else {
            setError('root', { message })
          }
        } else {
          setError('root', { message: 'Something went wrong. Please try again.' })
        }
      },
    })
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate>

      {/* Global error banner */}
      {errors.root && (
        <div className="flex items-center gap-2 bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2.5 mb-4">
          <svg className="w-4 h-4 text-red-400 shrink-0" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
          </svg>
          <p className="text-red-400 text-sm">{errors.root.message}</p>
        </div>
      )}

      {/* Display name */}
      <div className="mb-4">
        <label htmlFor="displayName" className="block text-sm font-medium text-gray-300 mb-1.5">
          Display name
        </label>
        <input
          id="displayName"
          type="text"
          autoComplete="name"
          placeholder="Alice"
          {...register('displayName')}
          className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition aria-invalid:border-red-500/50"
          aria-invalid={!!errors.displayName}
          aria-describedby={errors.displayName ? 'displayName-error' : 'displayName-hint'}
        />
        {errors.displayName ? (
          <p id="displayName-error" className="text-red-400 text-xs mt-1">{errors.displayName.message}</p>
        ) : (
          <p id="displayName-hint" className="text-gray-500 text-xs mt-1">2–30 characters. This is what others see.</p>
        )}
      </div>

      {/* Username */}
      <div className="mb-4">
        <label htmlFor="username" className="block text-sm font-medium text-gray-300 mb-1.5">
          Username
        </label>
        <div className="relative">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500 text-sm select-none">@</span>
          <input
            id="username"
            type="text"
            autoComplete="username"
            placeholder="alice_92"
            {...register('username')}
            className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg pl-7 pr-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition aria-invalid:border-red-500/50"
            aria-invalid={!!errors.username}
            aria-describedby={errors.username ? 'username-error' : 'username-hint'}
          />
        </div>
        {errors.username ? (
          <p id="username-error" className="text-red-400 text-xs mt-1">{errors.username.message}</p>
        ) : (
          <p id="username-hint" className="text-gray-500 text-xs mt-1">3–50 characters. Letters, digits and underscores only.</p>
        )}
      </div>

      {/* Email */}
      <div className="mb-4">
        <label htmlFor="email" className="block text-sm font-medium text-gray-300 mb-1.5">
          Email
        </label>
        <input
          id="email"
          type="email"
          autoComplete="email"
          placeholder="alice@example.com"
          {...register('email')}
          className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition aria-invalid:border-red-500/50"
          aria-invalid={!!errors.email}
          aria-describedby={errors.email ? 'email-error' : undefined}
        />
        {errors.email && (
          <p id="email-error" className="text-red-400 text-xs mt-1">{errors.email.message}</p>
        )}
      </div>

      {/* Password */}
      <div className="mb-6">
        <label htmlFor="password" className="block text-sm font-medium text-gray-300 mb-1.5">
          Password
        </label>
        <input
          id="password"
          type="password"
          autoComplete="new-password"
          placeholder="••••••••"
          {...register('password')}
          className="w-full bg-gray-800 border border-gray-700 text-white placeholder-gray-500 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition aria-invalid:border-red-500/50"
          aria-invalid={!!errors.password}
          aria-describedby={errors.password ? 'password-error' : 'password-hint'}
        />
        {errors.password ? (
          <p id="password-error" className="text-red-400 text-xs mt-1">{errors.password.message}</p>
        ) : (
          <p id="password-hint" className="text-gray-500 text-xs mt-1">Min 8 chars · 1 digit · 1 special character (@$!%*?&)</p>
        )}
      </div>

      {/* Submit */}
      <button
        type="submit"
        disabled={isPending}
        className="w-full bg-indigo-600 hover:bg-indigo-500 disabled:opacity-60 disabled:cursor-not-allowed text-white font-medium rounded-lg py-2.5 text-sm transition"
      >
        {isPending ? 'Creating account...' : 'Create account'}
      </button>

      {/* Login link */}
      <p className="text-center text-sm text-gray-500 mt-6">
        Already have an account?{' '}
        <Link to="/login" className="text-indigo-400 hover:text-indigo-300 font-medium transition">
          Sign in
        </Link>
      </p>

    </form>
  )
}
