import '@testing-library/jest-dom'
import { server } from './mswServer'

// Start MSW before all tests, reset handlers after each test so
// overrides in one test don't bleed into the next, shut down after all.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
