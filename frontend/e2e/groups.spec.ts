import { test, expect } from '@playwright/test'
import { USER_A_AUTH, USER_B_AUTH } from './global-setup'

// Unique suffix per test run — groups are never shared between runs.
const uid = Date.now()

// All group tests run as User A (already authenticated via storageState).
test.use({ storageState: USER_A_AUTH })

test.describe('Groups', () => {
  test.beforeEach(async ({ page }) => {
    // Groups list lives at / (root), not /groups.
    await page.goto('/')
  })

  // ─── Create ────────────────────────────────────────────────────────────────

  test('creates a group and shows it in the list', async ({ page }) => {
    const groupName = `Paris Trip ${uid}`

    await page.getByRole('button', { name: /new group/i }).click()
    await page.getByLabel('Group name').fill(groupName)
    await page.getByRole('button', { name: /create group/i }).click()

    // Modal closes and new group appears in the list.
    await expect(page.getByRole('dialog')).not.toBeVisible()
    await expect(page.getByText(groupName)).toBeVisible()
  })

  test('shows validation error when group name is too short', async ({ page }) => {
    await page.getByRole('button', { name: /new group/i }).click()
    await page.getByLabel('Group name').fill('AB')
    await page.getByRole('button', { name: /create group/i }).click()

    await expect(page.getByText(/at least 3 characters/i)).toBeVisible()
  })

  // ─── Join ──────────────────────────────────────────────────────────────────

  test('shows error when invite code is not found', async ({ page }) => {
    await page.getByRole('button', { name: /join group/i }).click()
    await page.getByLabel('Invite code').fill('NOTFOUND')
    await page.getByRole('button', { name: /^join group$/i }).click()

    await expect(page.getByText(/invite code not found/i)).toBeVisible()
  })

  // ─── Leave ─────────────────────────────────────────────────────────────────

  test('ADMIN does not see a leave button on their own group', async ({ page }) => {
    const groupName = `AdminGroup ${uid}`

    await page.getByRole('button', { name: /new group/i }).click()
    await page.getByLabel('Group name').fill(groupName)
    await page.getByRole('button', { name: /create group/i }).click()
    await expect(page.getByText(groupName)).toBeVisible()

    // The creator is ADMIN — backend enforces no self-leave for admins.
    // The leave button must not be visible.
    const card = page.locator('div').filter({ hasText: groupName }).first()
    await expect(card.getByRole('button', { name: /leave/i })).not.toBeVisible()
  })

  // ─── Navigation ────────────────────────────────────────────────────────────

  test('navigates into chat when a group card is clicked', async ({ page }) => {
    const groupName = `NavTest ${uid}`

    await page.getByRole('button', { name: /new group/i }).click()
    await page.getByLabel('Group name').fill(groupName)
    await page.getByRole('button', { name: /create group/i }).click()
    await expect(page.getByText(groupName)).toBeVisible()

    await page.getByText(groupName).click()
    // Chat URL is /groups/:id
    await expect(page).toHaveURL(/\/groups\//)
  })
})

// ─── Cross-user join flow ─────────────────────────────────────────────────────
// User A creates a group, User B joins it via invite code.

test('User B can join a group created by User A', async ({ browser }) => {
  const groupName = `JoinFlow ${uid}`

  // Context A — User A creates the group.
  const contextA = await browser.newContext({ storageState: USER_A_AUTH })
  const pageA = await contextA.newPage()
  await pageA.goto('/')

  await pageA.getByRole('button', { name: /new group/i }).click()
  await pageA.getByLabel('Group name').fill(groupName)
  await pageA.getByRole('button', { name: /create group/i }).click()
  await expect(pageA.getByText(groupName)).toBeVisible()

  // Read the invite code for the newly created group via API.
  const token = await pageA.evaluate(() => {
    const raw = localStorage.getItem('tripchat-auth')
    return raw ? (JSON.parse(raw) as { state: { token: string } }).state.token : ''
  })
  const groupsRes = await pageA.request.fetch('http://localhost:8080/api/v1/groups', {
    headers: { Authorization: `Bearer ${token}` },
  })
  const groupList = await groupsRes.json() as Array<{ name: string; inviteCode: string }>
  const inviteCode = groupList.find((g) => g.name === groupName)?.inviteCode
  expect(inviteCode).toBeTruthy()

  // Context B — User B joins using that invite code.
  const contextB = await browser.newContext({ storageState: USER_B_AUTH })
  const pageB = await contextB.newPage()
  await pageB.goto('/')

  await pageB.getByRole('button', { name: /join group/i }).click()
  await pageB.getByLabel('Invite code').fill(inviteCode!)
  await pageB.getByRole('button', { name: /^join group$/i }).click()

  await expect(pageB.getByText(groupName)).toBeVisible()

  await contextA.close()
  await contextB.close()
})
