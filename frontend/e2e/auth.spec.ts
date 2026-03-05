import { test, expect } from '@playwright/test'

// Unique suffix per test run so we never collide with existing users.
const uid = Date.now()

// ─── Registration ──────────────────────────────────────────────────────────────

test.describe('Registration', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/register')
  })

  test('shows validation errors when form is submitted empty', async ({ page }) => {
    await page.getByRole('button', { name: /create account/i }).click()

    await expect(page.getByText('Enter a valid email')).toBeVisible()
  })

  test('shows error for password missing special character', async ({ page }) => {
    await page.getByLabel('Email').fill(`user${uid}@test.com`)
    await page.getByLabel('Password').fill('NoSpecial1')
    await page.getByRole('button', { name: /create account/i }).click()

    await expect(page.getByText(/special character/i)).toBeVisible()
  })

  test('registers successfully and redirects to the groups list', async ({ page }) => {
    await page.getByLabel('Email').fill(`user${uid}@test.com`)
    await page.getByLabel('Password').fill('Test123!')
    await page.getByLabel('Username').fill(`user_${uid}`)
    await page.getByLabel('Display name').fill('Test User')
    await page.getByRole('button', { name: /create account/i }).click()

    // After registration the app navigates to / (the groups list page).
    await expect(page).toHaveURL('http://localhost:5173/')
  })

  test('shows error when email is already taken', async ({ page }) => {
    // e2e_a@tripchat.test is seeded in global-setup — guaranteed to exist.
    await page.getByLabel('Email').fill('e2e_a@tripchat.test')
    await page.getByLabel('Password').fill('Test123!')
    await page.getByLabel('Username').fill(`newuser_${uid}`)
    await page.getByLabel('Display name').fill('New User')
    await page.getByRole('button', { name: /create account/i }).click()

    await expect(page.getByText(/email.*taken/i)).toBeVisible()
  })
})

// ─── Login ─────────────────────────────────────────────────────────────────────

test.describe('Login', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/login')
  })

  test('shows error for wrong credentials', async ({ page }) => {
    await page.getByLabel('Email').fill('wrong@test.com')
    await page.getByLabel('Password').fill('Wrong123!')
    await page.getByRole('button', { name: /sign in/i }).click()

    await expect(page.getByText(/invalid email or password/i)).toBeVisible()
  })

  test('logs in and redirects to the groups list', async ({ page }) => {
    await page.getByLabel('Email').fill('e2e_a@tripchat.test')
    await page.getByLabel('Password').fill('Test123!')
    await page.getByRole('button', { name: /sign in/i }).click()

    // Login redirects to / (the groups list), not /groups.
    await expect(page).toHaveURL('http://localhost:5173/')
  })

  test('unauthenticated user visiting / is redirected to /login', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/\/login/)
  })
})
