import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { createKeyStore } from './api/apiKey.js'
import { ApiError } from './api/client.js'
import { createMockApi } from './api/mock.js'

const GOOD_KEY = 'ql_test_' + 'G'.repeat(43)
const OTHER_KEY = 'ql_test_' + 'O'.repeat(43)

const KEM_KEY = {
  keyPairId: '7a2f91c4-0000-4000-8000-000000000001',
  algorithm: 'ML-KEM-1024',
  status: 'active',
  publicKey: 'TUxLRU0tcHVibGljLWtleQ==',
  createdAt: '2026-11-21T15:04:00Z',
}

function memoryStorage() {
  const items = new Map()
  return {
    items,
    getItem: (name) => (items.has(name) ? items.get(name) : null),
    setItem: (name, value) => items.set(name, value),
    removeItem: (name) => items.delete(name),
  }
}

/** A mock API that, like the real one, answers 401 unless the store holds GOOD_KEY. */
function guardedApi(keyStore) {
  const api = createMockApi({ initialKeys: [KEM_KEY], delayMs: 0 })
  const guard = (call) => (...args) =>
    keyStore.get() === GOOD_KEY ? call(...args) : Promise.reject(new ApiError(401, 'unauthorized'))
  return { listKeys: guard(api.listKeys), generateKey: guard(api.generateKey) }
}

function setup({ storage = memoryStorage() } = {}) {
  const keyStore = createKeyStore(storage)
  const api = guardedApi(keyStore)
  const user = userEvent.setup()
  render(<App api={api} keyStore={keyStore} mock={false} />)
  return { user, keyStore, storage, api }
}

describe('API key entry', () => {
  it('asks for a key before showing any keys, in a hidden field', () => {
    setup()

    expect(screen.getByRole('heading', { name: 'Enter your API key' })).toBeInTheDocument()
    expect(screen.getByLabelText('API key')).toHaveAttribute('type', 'password')
    expect(screen.getByLabelText('API key')).toHaveAttribute('autocomplete', 'off')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('shows the keys once the API accepts the key, and keeps it out of storage', async () => {
    const { user, storage } = setup()

    await user.type(screen.getByLabelText('API key'), GOOD_KEY)
    await user.click(screen.getByRole('button', { name: 'Use this key' }))

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByText('7a2f91c4')).toBeInTheDocument()
    expect(storage.items.size).toBe(0)
    expect(document.body.textContent).not.toContain(GOOD_KEY)
  })

  it('remembers the key for the tab only when asked', async () => {
    const { user, storage } = setup()

    await user.type(screen.getByLabelText('API key'), GOOD_KEY)
    await user.click(screen.getByRole('checkbox', { name: /Remember until this browser tab is closed/ }))
    await user.click(screen.getByRole('button', { name: 'Use this key' }))
    await screen.findByRole('table')

    expect([...storage.items.values()]).toEqual([GOOD_KEY])
  })

  it('refuses text that is not a key without calling the API', async () => {
    const { user, keyStore } = setup()
    const spy = vi.spyOn(keyStore, 'set')

    await user.type(screen.getByLabelText('API key'), 'hunter2')
    await user.click(screen.getByRole('button', { name: 'Use this key' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not a QuantumLock API key/)
    expect(spy).not.toHaveBeenCalled()
  })

  it('explains a key the API refuses and forgets it', async () => {
    const { user, keyStore } = setup()

    await user.type(screen.getByLabelText('API key'), OTHER_KEY)
    await user.click(screen.getByRole('button', { name: 'Use this key' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/did not accept this API key/)
    expect(keyStore.get()).toBeNull()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('goes straight to the keys with a key remembered from earlier in the tab', async () => {
    const storage = memoryStorage()
    storage.setItem('quantumlock.apiKey', GOOD_KEY)
    setup({ storage })

    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('returns to key entry when the key stops working, for example after revocation', async () => {
    const { user, keyStore } = setup()
    await user.type(screen.getByLabelText('API key'), GOOD_KEY)
    await user.click(screen.getByRole('button', { name: 'Use this key' }))
    await screen.findByRole('table')

    keyStore.set(OTHER_KEY) // as if GOOD_KEY had been revoked
    await user.click(screen.getByRole('button', { name: 'Generate key pair' }))

    expect(await screen.findByRole('heading', { name: 'Enter your API key' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/no longer accepts this key/)
    expect(keyStore.get()).toBeNull()
  })

  it('forgets the key on request', async () => {
    const storage = memoryStorage()
    storage.setItem('quantumlock.apiKey', GOOD_KEY)
    const { user, keyStore } = setup({ storage })
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: 'Forget API key' }))

    expect(screen.getByRole('heading', { name: 'Enter your API key' })).toBeInTheDocument()
    expect(keyStore.get()).toBeNull()
    expect(storage.items.size).toBe(0)
  })
})
