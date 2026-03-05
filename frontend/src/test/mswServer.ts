import { setupServer } from 'msw/node'
import { authHandlers } from './handlers/auth'
import { groupHandlers } from './handlers/groups'
import { chatHandlers } from './handlers/chat'

// Single MSW server used across all tests.
// Handlers are registered here; tests can override with server.use() for specific cases.
export const server = setupServer(...authHandlers, ...groupHandlers, ...chatHandlers)
