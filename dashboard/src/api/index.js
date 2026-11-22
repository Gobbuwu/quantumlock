import { createApi } from './client.js'
import { createMockApi } from './mock.js'

/** The real backend, or made-up data when started with "npm run dev:mock". */
export const api = import.meta.env.VITE_API_MODE === 'mock' ? createMockApi() : createApi()
