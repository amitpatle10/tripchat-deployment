/**
 * TripChat — k6 Load Test Scenarios
 * ====================================
 * Tests all 6 group + auth APIs under concurrent load.
 *
 * Pre-requisite: run seed.py first to populate seed-output.json
 *
 * Usage:
 *   k6 run scenarios.js                                        (full test ~4m)
 *   k6 run --env SMOKE=true scenarios.js                       (smoke test ~30s)
 *   k6 run --env BASE_URL=http://your-server:8080 scenarios.js (remote target)
 *
 * Scenarios (1000 total VUs at peak):
 *   auth_flow       (  50 VUs) — login every iteration, measures BCrypt throughput
 *   group_read      ( 400 VUs) — list groups + get by ID, most common operation
 *   join_full_group ( 200 VUs) — join a full group → expects 400 GroupFullException
 *   already_member  ( 150 VUs) — join a group already in → expects 409
 *   join_and_leave  ( 100 VUs) — join empty group, leave, repeat (clean cycle)
 *   create_group    ( 100 VUs) — create a new group, measures write path
 *
 * Ramp strategy per scenario:
 *   0s → 30s  ramp up to target VUs
 *   30s → 3m  hold at peak (steady state)
 *   3m → 3m15s ramp down
 *   Total wall-clock: ~3m45s
 */

import http        from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray }  from 'k6/data'

// ── Load seed data ────────────────────────────────────────────────────────────
//
// open() reads the file at init time (before any VU starts).
// SharedArray loads data ONCE into shared memory — all VUs read from the same
// allocation. Without SharedArray, each of the 1000 VUs would get its own copy
// of the 1000-user array → ~50MB wasted. SharedArray keeps it at ~50KB total.

const seed = JSON.parse(open('../seed-output.json'))

const testUsers   = new SharedArray('testUsers',   () => seed.testUsers)   // 1000 users
const freeUsers   = new SharedArray('freeUsers',   () => seed.freeUsers)   // 100  users, no pre-assigned groups
const fullGroups  = new SharedArray('fullGroups',  () => seed.groups.full) // 3 groups at 1000-member cap
const largeGroups = new SharedArray('largeGroups', () => seed.groups.large)// 5 groups, 500 members
const emptyGroups = new SharedArray('emptyGroups', () => seed.groups.empty)// 5 groups, 0 members

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const PASSWORD  = seed.password

// SMOKE=true → short stages (5s ramp + 15s hold + 5s down) for quick validation
// default    → full stages  (30s ramp + 3m hold  + 15s down) for real load test
const SMOKE = __ENV.SMOKE === 'true'

function stages(targetVUs) {
  return SMOKE
    ? [{ duration: '5s',  target: Math.min(targetVUs, 3) },
       { duration: '15s', target: Math.min(targetVUs, 3) },
       { duration: '5s',  target: 0 }]
    : [{ duration: '30s', target: targetVUs },
       { duration: '3m',  target: targetVUs },
       { duration: '15s', target: 0 }]
}

// ── Response callback ─────────────────────────────────────────────────────────
//
// By default k6 counts any 4xx/5xx as http_req_failed.
// Some scenarios intentionally expect 400 (GroupFull) and 409 (AlreadyMember).
// Marking them as expected keeps the http_req_failed metric meaningful —
// only truly unexpected failures (e.g., 500, timeout, wrong 4xx) count.
//
// Correctness is still enforced: check() verifies the exact status code,
// and the `checks` threshold (rate>0.99) catches any wrong responses.
http.setResponseCallback(
  http.expectedStatuses({ min: 200, max: 299 }, 400, 409)
)

// ── Options ───────────────────────────────────────────────────────────────────

export const options = {
  scenarios: {

    // 1. Auth — measures login throughput under concurrent load
    //    Every iteration logs in fresh (no caching) — that IS the thing being measured.
    //    BCrypt at cost 10 means ~200ms/request. 50 concurrent = ~250 req/s auth capacity.
    auth_flow: {
      executor: 'ramping-vus',
      stages:   stages(50),
      exec:     'authFlow',
    },

    // 2. Group read — most common real-world operation
    //    testuser0001-0500 are each in 5 large groups (500 members each).
    //    Pattern: open app → see list → tap a group → see details.
    group_read: {
      executor: 'ramping-vus',
      stages:   stages(400),
      exec:     'groupRead',
    },

    // 3. Join full group — error path benchmark
    //    testUsers are NOT in any full group (only fillerUsers are).
    //    Every join attempt hits the capacity check → GroupFullException.
    //    Measures: how fast is the error path? DB read + count check must be <30ms.
    join_full_group: {
      executor:  'ramping-vus',
      startTime: SMOKE ? '0s' : '30s',   // start after auth warms up the JVM
      stages:    stages(200),
      exec:      'joinFullGroup',
    },

    // 4. Already member — duplicate-check fast path
    //    testuser0001-0500 are already in all 5 large groups.
    //    Every join attempt hits the existsByGroupAndUser check → AlreadyMemberException.
    already_member: {
      executor: 'ramping-vus',
      stages:   stages(150),
      exec:     'alreadyMember',
    },

    // 5. Join and leave — repeatable join/leave cycle
    //    freeUsers (0901-1000) start with no memberships.
    //    Pattern: join empty group → spend time → leave → next iteration starts clean.
    //    Leaving at the end is what makes this scenario repeatable across iterations.
    join_and_leave: {
      executor: 'ramping-vus',
      stages:   stages(100),
      exec:     'joinAndLeave',
    },

    // 6. Create group — write path benchmark
    //    freeUsers create new groups. Each created group is left in the DB —
    //    this is a load test environment, cleanup is not needed.
    create_group: {
      executor: 'ramping-vus',
      stages:   stages(100),
      exec:     'createGroup',
    },
  },

  thresholds: {
    // ── Global ────────────────────────────────────────────────────────────────
    // 95% of ALL requests complete in < 100ms (our CLAUDE.md target)
    // 99% complete in < 500ms (generous tail latency)
    http_req_duration: ['p(95)<100', 'p(99)<500'],

    // Unexpected failures (status codes not in expectedStatuses) < 1%
    http_req_failed: ['rate<0.01'],

    // At least 99% of all check() assertions must pass
    checks: ['rate>0.99'],

    // ── Per-scenario ──────────────────────────────────────────────────────────
    // Read operations must be faster — no writes, should hit indexes directly
    'http_req_duration{scenario:group_read}':     ['p(95)<50'],

    // Error paths must be fast — no DB writes, just a read + exception
    // 50ms gives headroom for cold JVM and concurrent load at 200/150 VUs
    'http_req_duration{scenario:join_full_group}': ['p(95)<50'],
    'http_req_duration{scenario:already_member}':  ['p(95)<50'],

    // Auth is intentionally slow (BCrypt cost 10 ≈ 200ms) — give it room
    'http_req_duration{scenario:auth_flow}':       ['p(95)<400'],
  },
}

// ── VU-local token cache ───────────────────────────────────────────────────────
//
// Each VU gets its own copy of this variable (not shared).
// Populated on the first iteration, reused for the rest of the VU's lifetime.
// Our JWT expires in 24h — safe to cache for a 5-minute test.
// authFlow is the only scenario that does NOT cache — it measures login itself.
let vuToken = null

// ── Helpers ───────────────────────────────────────────────────────────────────

const CT_JSON = { 'Content-Type': 'application/json' }

function authOpts(token) {
  return {
    headers: {
      'Content-Type':  'application/json',
      'Authorization': `Bearer ${token}`,
    },
  }
}

function doLogin(email) {
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password: PASSWORD }),
    { headers: CT_JSON }
  )
  check(res, { 'login: 200 OK': r => r.status === 200 })
  return res.status === 200 ? res.json('token') : null
}

// Returns cached token, logging in once per VU lifetime
function getToken(email) {
  if (!vuToken) vuToken = doLogin(email)
  return vuToken
}

// Realistic think time between actions — simulates user reading the screen
function think() {
  sleep(0.5 + Math.random() * 1.5)  // 0.5s – 2.0s
}

// ── Scenario: auth_flow ───────────────────────────────────────────────────────
//
// Logs in every single iteration — no caching.
// This is the entire point of the scenario: stress-test BCrypt login.
// With 50 VUs and ~200ms per login, server handles ~250 logins/second.

export function authFlow() {
  const user = testUsers[__VU % testUsers.length]

  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: user.email, password: PASSWORD }),
    { headers: CT_JSON }
  )

  check(res, {
    'auth: status 200':    r => r.status === 200,
    'auth: has token':     r => typeof r.json('token') === 'string' && r.json('token').length > 0,
    'auth: has user':      r => r.json('user') !== null,
    'auth: expiresIn 24h': r => r.json('expiresIn') === 86400000,
  })

  think()
}

// ── Scenario: group_read ──────────────────────────────────────────────────────
//
// The most common app flow: open the app → see your groups → tap one.
// Uses testuser0001-0500 who are each in 5 large groups (500 members each).
// Token is cached per VU — login overhead doesn't pollute group read metrics.

export function groupRead() {
  const user  = testUsers[__VU % 500]   // indices 0-499 → testuser0001-0500 (in large groups)
  const token = getToken(user.email)
  if (!token) return

  // Step 1 — List all my groups
  let res = http.get(`${BASE_URL}/api/v1/groups`, authOpts(token))

  const listOk = check(res, {
    'group_read: list 200':      r => r.status === 200,
    'group_read: is array':      r => Array.isArray(r.json()),
    'group_read: has groups':    r => r.json().length > 0,
    'group_read: has inviteCode':r => r.json()[0].inviteCode !== undefined,
  })

  if (!listOk) return

  sleep(0.5)  // user scans the list

  // Step 2 — Fetch a random group from the list
  const myGroups = res.json()
  const picked   = myGroups[Math.floor(Math.random() * myGroups.length)]

  res = http.get(`${BASE_URL}/api/v1/groups/${picked.id}`, authOpts(token))

  check(res, {
    'group_read: get 200':        r => r.status === 200,
    'group_read: id matches':     r => r.json('id') === picked.id,
    'group_read: memberCount > 0':r => r.json('memberCount') > 0,
    'group_read: myRole present': r => ['ADMIN', 'MEMBER'].includes(r.json('myRole')),
  })

  think()
}

// ── Scenario: join_full_group ─────────────────────────────────────────────────
//
// Hammers the GroupFullException path.
// testUsers are NOT in any full group → every join attempt returns 400.
// Validates that the capacity check is fast even under 200 concurrent requests.
// The 400 is the SUCCESS condition here — a 200 would mean data is wrong.

export function joinFullGroup() {
  const user  = testUsers[__VU % testUsers.length]
  const token = getToken(user.email)
  if (!token) return

  // Spread load across all 3 full groups
  const group = fullGroups[__VU % fullGroups.length]

  const res = http.post(
    `${BASE_URL}/api/v1/groups/join`,
    JSON.stringify({ inviteCode: group.inviteCode }),
    authOpts(token)
  )

  check(res, {
    'join_full: 400 returned':  r => r.status === 400,
    'join_full: has message':   r => typeof r.json('message') === 'string',
    'join_full: has status':    r => r.json('status') === 400,
  })

  think()
}

// ── Scenario: already_member ──────────────────────────────────────────────────
//
// Hammers the AlreadyMemberException path.
// testuser0001-0500 are already in all 5 large groups → 409 on every attempt.
// Validates that the existsByGroupAndUser check is fast under 150 concurrent requests.

export function alreadyMember() {
  const user  = testUsers[__VU % 500]   // all in large groups
  const token = getToken(user.email)
  if (!token) return

  // Spread load across all 5 large groups
  const group = largeGroups[__VU % largeGroups.length]

  const res = http.post(
    `${BASE_URL}/api/v1/groups/join`,
    JSON.stringify({ inviteCode: group.inviteCode }),
    authOpts(token)
  )

  check(res, {
    'already_member: 409 returned': r => r.status === 409,
    'already_member: has message':  r => typeof r.json('message') === 'string',
    'already_member: has status':   r => r.json('status') === 409,
  })

  think()
}

// ── Scenario: join_and_leave ──────────────────────────────────────────────────
//
// Repeatable join → leave cycle. freeUsers (testuser0901-1000) start with
// no group memberships, so every join succeeds. Leaving at the end resets
// state — the next iteration is identical to the first.
//
// Concurrency note: multiple VUs may join the same empty group at once.
// That's fine — they're different users, and the 1000-member cap won't
// be reached (5 groups × at most 100 members = 500, well below cap).

export function joinAndLeave() {
  const user  = freeUsers[__VU % freeUsers.length]
  const token = getToken(user.email)
  if (!token) return

  // Each VU consistently maps to one empty group (not random — avoids
  // the same user trying to double-join the same group across iterations)
  const group = emptyGroups[__VU % emptyGroups.length]

  // Step 1 — Join
  let res = http.post(
    `${BASE_URL}/api/v1/groups/join`,
    JSON.stringify({ inviteCode: group.inviteCode }),
    authOpts(token)
  )

  const joined = check(res, {
    'join_leave: join 200':      r => r.status === 200,
    'join_leave: role is MEMBER':r => r.json('myRole') === 'MEMBER',
    'join_leave: memberCount++': r => r.json('memberCount') > 1,   // > 1 because admin is already there
  })

  if (!joined) return

  const groupId = res.json('id')
  sleep(1)  // user spends a moment in the group

  // Step 2 — Leave (cleanup — makes next iteration repeatable)
  res = http.del(
    `${BASE_URL}/api/v1/groups/${groupId}/leave`,
    null,
    authOpts(token)
  )

  check(res, {
    'join_leave: leave 204': r => r.status === 204,
  })

  think()
}

// ── Scenario: create_group ────────────────────────────────────────────────────
//
// Measures the group creation write path under load.
// freeUsers create groups — they have no pre-existing groups so there's
// no state conflict with join_and_leave running concurrently on the same pool.
// Created groups are intentionally left in DB — load test environment.

export function createGroup() {
  const user  = freeUsers[__VU % freeUsers.length]
  const token = getToken(user.email)
  if (!token) return

  // Unique name per VU per iteration — avoids any name collision edge cases
  const name = `LT ${__VU}-${Date.now()}`

  const res = http.post(
    `${BASE_URL}/api/v1/groups`,
    JSON.stringify({ name, description: 'Created during load test' }),
    authOpts(token)
  )

  check(res, {
    'create: 201 returned':        r => r.status === 201,
    'create: has id':              r => typeof r.json('id') === 'string',
    'create: inviteCode 8 chars':  r => r.json('inviteCode') !== undefined && r.json('inviteCode').length === 8,
    'create: myRole is ADMIN':     r => r.json('myRole') === 'ADMIN',
    'create: memberCount is 1':    r => r.json('memberCount') === 1,
  })

  think()
}
