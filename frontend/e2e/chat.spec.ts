import { test, expect } from '@playwright/test'
import { USER_A_AUTH, USER_B_AUTH } from './global-setup'

const uid = Date.now()

// Helper — reads the JWT from Zustand's persisted localStorage entry.
async function getToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => {
    const raw = localStorage.getItem('tripchat-auth')
    return raw ? (JSON.parse(raw) as { state: { token: string } }).state.token : ''
  })
}

// Helper — creates a group via API and returns its id + inviteCode.
async function createGroup(
  page: import('@playwright/test').Page,
  name: string,
): Promise<{ id: string; inviteCode: string }> {
  const token = await getToken(page)
  const res = await page.request.fetch('http://localhost:8080/api/v1/groups', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: JSON.stringify({ name }),
  })
  return res.json()
}

// Helper — joins a group via API (skips the UI, tests only chat behaviour).
async function joinGroup(
  page: import('@playwright/test').Page,
  inviteCode: string,
): Promise<void> {
  const token = await getToken(page)
  await page.request.fetch('http://localhost:8080/api/v1/groups/join', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: JSON.stringify({ inviteCode }),
  })
}

// ─── Single-user send ─────────────────────────────────────────────────────────

test.describe('Chat — send message', () => {
  test.use({ storageState: USER_A_AUTH })

  test('sent message appears in the chat immediately', async ({ page }) => {
    await page.goto('/')
    const group = await createGroup(page, `ChatSend ${uid}`)
    await page.goto(`/groups/${group.id}`)

    await page.getByLabel('Message input').fill('Hello from E2E!')
    await page.getByRole('button', { name: /send message/i }).click()

    await expect(page.getByText('Hello from E2E!')).toBeVisible()
  })

  test('textarea clears after sending', async ({ page }) => {
    await page.goto('/')
    const group = await createGroup(page, `ChatClear ${uid}`)
    await page.goto(`/groups/${group.id}`)

    const input = page.getByLabel('Message input')
    await input.fill('Clear me')
    await page.getByRole('button', { name: /send message/i }).click()

    await expect(input).toHaveValue('')
  })

  test('Enter key sends the message', async ({ page }) => {
    await page.goto('/')
    const group = await createGroup(page, `ChatEnter ${uid}`)
    await page.goto(`/groups/${group.id}`)

    await page.getByLabel('Message input').fill('Sent with Enter')
    await page.getByLabel('Message input').press('Enter')

    await expect(page.getByText('Sent with Enter')).toBeVisible()
  })

  test('Shift+Enter inserts a newline without sending', async ({ page }) => {
    await page.goto('/')
    const group = await createGroup(page, `ChatShiftEnter ${uid}`)
    await page.goto(`/groups/${group.id}`)

    const input = page.getByLabel('Message input')
    await input.fill('Line one')
    await input.press('Shift+Enter')
    await input.type('Line two')

    // Input still has content — nothing was sent.
    await expect(input).not.toHaveValue('')
    await expect(page.getByText('Line one')).not.toBeVisible()
  })
})

// ─── Real-time delivery (two users) ──────────────────────────────────────────
//
// User A sends a message. User B must see it appear without refreshing.
// Two separate browser contexts = two independent WebSocket connections.

test('User A sends a message and User B receives it in real time', async ({ browser }) => {
  const groupName = `RealTime ${uid}`

  const contextA = await browser.newContext({ storageState: USER_A_AUTH })
  const contextB = await browser.newContext({ storageState: USER_B_AUTH })
  const pageA = await contextA.newPage()
  const pageB = await contextB.newPage()

  // Navigate to / first so localStorage is accessible for API helpers.
  await pageA.goto('/')
  await pageB.goto('/')

  const group = await createGroup(pageA, groupName)
  await joinGroup(pageB, group.inviteCode)

  // Both users open the same chat room.
  await pageA.goto(`/groups/${group.id}`)
  await pageB.goto(`/groups/${group.id}`)

  // User A sends.
  await pageA.getByLabel('Message input').fill('Hey from User A!')
  await pageA.getByRole('button', { name: /send message/i }).click()

  // User A sees their own message (optimistic, then confirmed).
  await expect(pageA.getByText('Hey from User A!')).toBeVisible()

  // User B receives via WebSocket — no refresh.
  await expect(pageB.getByText('Hey from User A!')).toBeVisible({ timeout: 8000 })

  // User B replies.
  await pageB.getByLabel('Message input').fill('Hey back from User B!')
  await pageB.getByRole('button', { name: /send message/i }).click()

  // User A receives the reply in real time.
  await expect(pageA.getByText('Hey back from User B!')).toBeVisible({ timeout: 8000 })

  await contextA.close()
  await contextB.close()
})

// ─── Typing indicator ─────────────────────────────────────────────────────────

test('User B sees typing indicator while User A is typing', async ({ browser }) => {
  const groupName = `Typing ${uid}`

  const contextA = await browser.newContext({ storageState: USER_A_AUTH })
  const contextB = await browser.newContext({ storageState: USER_B_AUTH })
  const pageA = await contextA.newPage()
  const pageB = await contextB.newPage()

  await pageA.goto('/')
  await pageB.goto('/')

  const group = await createGroup(pageA, groupName)
  await joinGroup(pageB, group.inviteCode)

  await pageA.goto(`/groups/${group.id}`)
  await pageB.goto(`/groups/${group.id}`)

  // User A starts typing.
  await pageA.getByLabel('Message input').fill('I am typing...')

  // User B sees the typing indicator (display name comes from the stored auth).
  await expect(pageB.getByText(/e2e user a is typing/i)).toBeVisible({ timeout: 6000 })

  // User A sends — indicator clears for User B.
  await pageA.getByRole('button', { name: /send message/i }).click()
  await expect(pageB.getByText(/is typing/i)).not.toBeVisible({ timeout: 6000 })

  await contextA.close()
  await contextB.close()
})
