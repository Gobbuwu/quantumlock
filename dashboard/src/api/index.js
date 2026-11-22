import { createKeyStore } from './apiKey.js'
import { createApi } from './client.js'
import { createMockApi } from './mock.js'

export const mock = import.meta.env.VITE_API_MODE === 'mock'

/** The API key the user entered. Mock mode needs none. */
export const keyStore = mock ? null : createKeyStore()

/** The real backend, or made-up data when started with "npm run dev:mock". */
export const api = mock ? createMockApi() : createApi(undefined, undefined, keyStore.get)
