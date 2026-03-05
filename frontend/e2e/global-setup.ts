import { chromium, request } from '@playwright/test'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// Paths where authenticated browser state is saved.
// Each test file that needs a logged-in user loads one of these.
export const USER_A_AUTH = path.join(__dirname, '.auth/userA.json')
export const USER_B_AUTH = path.join(__dirname, '.auth/userB.json')

// Fixed test credentials — stable across runs so the DB accumulates one row per user.
export const USER_A = {
  email: 'e2e_a@tripchat.test',
  password: 'Test123!',
  username: 'e2e_user_a',
  displayName: 'E2E User A',
}

export const USER_B = {
  email: 'e2e_b@tripchat.test',
  password: 'Test123!',
  username: 'e2e_user_b',
  displayName: 'E2E User B',
}

// Register a user via the API. Silently ignores 409 (already exists) so this
// is idempotent — safe to run on every test invocation.
async function ensureUserExists(credentials: typeof USER_A) {
  const api = await request.newContext({ baseURL: 'http://localhost:8080' })
  const res = await api.post('/api/v1/auth/register', { data: credentials })
  if (!res.ok() && res.status() !== 409) {
    throw new Error(
      `Failed to register ${credentials.email}: ${res.status()} ${await res.text()}`,
    )
  }
  await api.dispose()
}

// Log in via the API, inject the JWT directly into localStorage in Zustand's
// persist format, then save storageState to disk.
//
// Why inject instead of filling the login form?
// Global setup runs in a plain browser context (no Playwright test fixtures).
// Injecting is faster, doesn't depend on DOM timing, and the login form UI is
// already covered by auth.spec.ts — no need to exercise it here again.
async function saveAuthState(
  credentials: { email: string; password: string },
  outputPath: string,
) {
  // 1. Get the JWT from the real API.
  const api = await request.newContext({ baseURL: 'http://localhost:8080' })
  const res = await api.post('/api/v1/auth/login', {
    data: { email: credentials.email, password: credentials.password },
  })
  if (!res.ok()) {
    throw new Error(`Login failed for ${credentials.email}: ${res.status()} ${await res.text()}`)
  }
  const { token, user } = await res.json() as { token: string; user: object }
  await api.dispose()

  // 2. Open a browser, navigate to the app, and write the token into
  //    localStorage in the exact format Zustand persist uses:
  //    { state: { user, token }, version: 0 }
  const browser = await chromium.launch()
  const context = await browser.newContext()
  const page = await context.newPage()

  await page.goto('http://localhost:5173/login')
  await page.evaluate(
    ([storedState]) => {
      localStorage.setItem('tripchat-auth', JSON.stringify(storedState))
    },
    [{ state: { user, token }, version: 0 }],
  )

  // 3. Navigate to / to confirm ProtectedRoute unblocks with the injected token.
  await page.goto('http://localhost:5173/')
  await page.waitForURL('http://localhost:5173/')

  // 4. Capture localStorage + cookies to disk.
  await context.storageState({ path: outputPath })
  await browser.close()
}

export default async function globalSetup() {
  // 1. Ensure both test users exist in the DB (idempotent).
  await ensureUserExists(USER_A)
  await ensureUserExists(USER_B)

  // 2. Log in as each user and save their auth state.
  await saveAuthState(USER_A, USER_A_AUTH)
  await saveAuthState(USER_B, USER_B_AUTH)
}
